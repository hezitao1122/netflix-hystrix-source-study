/**
 * Copyright 2013 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix;

import com.netflix.hystrix.HystrixCircuitBreaker.NoOpCircuitBreaker;
import com.netflix.hystrix.HystrixCommandProperties.ExecutionIsolationStrategy;
import com.netflix.hystrix.exception.ExceptionNotWrappedByHystrix;
import com.netflix.hystrix.exception.HystrixBadRequestException;
import com.netflix.hystrix.exception.HystrixRuntimeException;
import com.netflix.hystrix.exception.HystrixRuntimeException.FailureType;
import com.netflix.hystrix.exception.HystrixTimeoutException;
import com.netflix.hystrix.strategy.HystrixPlugins;
import com.netflix.hystrix.strategy.concurrency.HystrixConcurrencyStrategy;
import com.netflix.hystrix.strategy.concurrency.HystrixContextRunnable;
import com.netflix.hystrix.strategy.concurrency.HystrixRequestContext;
import com.netflix.hystrix.strategy.eventnotifier.HystrixEventNotifier;
import com.netflix.hystrix.strategy.executionhook.HystrixCommandExecutionHook;
import com.netflix.hystrix.strategy.metrics.HystrixMetricsPublisherFactory;
import com.netflix.hystrix.strategy.properties.HystrixPropertiesFactory;
import com.netflix.hystrix.strategy.properties.HystrixPropertiesStrategy;
import com.netflix.hystrix.strategy.properties.HystrixProperty;
import com.netflix.hystrix.util.HystrixTimer;
import com.netflix.hystrix.util.HystrixTimer.TimerListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Notification;
import rx.Observable;
import rx.Observable.Operator;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.subjects.ReplaySubject;
import rx.subscriptions.CompositeSubscription;

import java.lang.ref.Reference;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/* package */abstract class AbstractCommand<R> implements HystrixInvokableInfo<R>, HystrixObservable<R> {
    private static final Logger logger = LoggerFactory.getLogger(AbstractCommand.class);
    /**
     * 断路器
     */
    protected final HystrixCircuitBreaker circuitBreaker;
    /**
     * 线程池
     */
    protected final HystrixThreadPool threadPool;
    protected final HystrixThreadPoolKey threadPoolKey;
    protected final HystrixCommandProperties properties;

    protected enum TimedOutStatus {
        NOT_EXECUTED, COMPLETED, TIMED_OUT
    }

    protected enum CommandState {
        NOT_STARTED, OBSERVABLE_CHAIN_CREATED, USER_CODE_EXECUTED, UNSUBSCRIBED, TERMINAL
    }

    protected enum ThreadState {
        NOT_USING_THREAD, STARTED, UNSUBSCRIBED, TERMINAL
    }

    protected final HystrixCommandMetrics metrics;

    protected final HystrixCommandKey commandKey;
    protected final HystrixCommandGroupKey commandGroup;

    /**
     * Plugin implementations
     */
    protected final HystrixEventNotifier eventNotifier;
    protected final HystrixConcurrencyStrategy concurrencyStrategy;
    protected final HystrixCommandExecutionHook executionHook;

    /* FALLBACK Semaphore */
    protected final TryableSemaphore fallbackSemaphoreOverride;
    /* each circuit has a semaphore to restrict concurrent fallback execution */
    protected static final ConcurrentHashMap<String, TryableSemaphore> fallbackSemaphorePerCircuit = new ConcurrentHashMap<String, TryableSemaphore>();
    /* END FALLBACK Semaphore */

    /* EXECUTION Semaphore */
    protected final TryableSemaphore executionSemaphoreOverride;
    /* each circuit has a semaphore to restrict concurrent fallback execution */
    protected static final ConcurrentHashMap<String, TryableSemaphore> executionSemaphorePerCircuit = new ConcurrentHashMap<String, TryableSemaphore>();
    /* END EXECUTION Semaphore */

    protected final AtomicReference<Reference<TimerListener>> timeoutTimer = new AtomicReference<Reference<TimerListener>>();

    protected AtomicReference<CommandState> commandState = new AtomicReference<CommandState>(CommandState.NOT_STARTED);
    protected AtomicReference<ThreadState> threadState = new AtomicReference<ThreadState>(ThreadState.NOT_USING_THREAD);

    /*
     * {@link ExecutionResult} refers to what happened as the user-provided code ran.  If request-caching is used,
     * then multiple command instances will have a reference to the same {@link ExecutionResult}.  So all values there
     * should be the same, even in the presence of request-caching.
     *
     * If some values are not properly shareable, then they belong on the command instance, so they are not visible to
     * other commands.
     *
     * Examples: RESPONSE_FROM_CACHE, CANCELLED HystrixEventTypes
     */
    protected volatile ExecutionResult executionResult = ExecutionResult.EMPTY; //state on shared execution

    protected volatile boolean isResponseFromCache = false;
    protected volatile ExecutionResult executionResultAtTimeOfCancellation;
    protected volatile long commandStartTimestamp = -1L;

    /* If this command executed and timed-out */
    protected final AtomicReference<TimedOutStatus> isCommandTimedOut = new AtomicReference<TimedOutStatus>(TimedOutStatus.NOT_EXECUTED);
    protected volatile Action0 endCurrentThreadExecutingCommand;

    /**
     * Instance of RequestCache logic
     */
    protected final HystrixRequestCache requestCache;
    protected final HystrixRequestLog currentRequestLog;

    // this is a micro-optimization but saves about 1-2microseconds (on 2011 MacBook Pro) 
    // on the repetitive string processing that will occur on the same classes over and over again
    private static ConcurrentHashMap<Class<?>, String> defaultNameCache = new ConcurrentHashMap<Class<?>, String>();

    protected static ConcurrentHashMap<HystrixCommandKey, Boolean> commandContainsFallback = new ConcurrentHashMap<HystrixCommandKey, Boolean>();

    /* package */static String getDefaultNameFromClass(Class<?> cls) {
        String fromCache = defaultNameCache.get(cls);
        if (fromCache != null) {
            return fromCache;
        }
        // generate the default
        // default HystrixCommandKey to use if the method is not overridden
        String name = cls.getSimpleName();
        if (name.equals("")) {
            // we don't have a SimpleName (anonymous inner class) so use the full class name
            name = cls.getName();
            name = name.substring(name.lastIndexOf('.') + 1, name.length());
        }
        defaultNameCache.put(cls, name);
        return name;
    }
    /** description: 相当于Command初始化的时候,通过Setter传入进来的一些参数 , 主要传入类型是Setter
     *
     * HystrixInvocationHandler的invoke()方法会调用线程池初始化HystrixCommand的构造方法,HystrixCommand会调用父类的AbstractCommand构造
     * 1. initThreadPool() 线程池的初始化
     *    1). 从一个Map里,根据服务名去获取一个threadPool
     *    2). 如果获取不到,就会新建一个threadPool,然后放到一个Map中去,并返回
     *    3). 构造的threadPool是HystrixThreadPoolDefault类型
     *    4). 获取到以下的配置,并给出默认配置项
     *      (1). hystrix.threadpool.ServiceA.allowMaximumSizeToDivergeFromCoreSize = false  是否支持和线程池动态扩容,默认不可动态线程
     *      (2). hystrix.threadpool.ServiceA.keepAliveTimeMinutes = 1
     *      (3). hystrix.threadpool.ServiceA.maximumSize = 10
     *      (4). hystrix.threadpool.ServiceA.coreSize = 10
     *      (5). hystrix.threadpool.ServiceA.maxQueueSize = -1  默认是-1  所以会创建SynchronousQueue,是不支持排队的
     *      (6). hystrix.threadpool.ServiceA.queueSizeRejectionThreshold = 5
     *      (7). hystrix.threadpool.ServiceA.metrics.rollingStats.numBuckets = 10
     *      (8). hystrix.threadpool.ServiceA.metrics.rollingStats.timeInMilliseconds = 10000
     *      (9). queueSize = properties.maxQueueSize().get() 配置等待队列的最大值
     *    5). concurrencyStrategy.getThreadPool(threadPoolKey, properties) 这段代码就是构建threadPool的方法
     *      (1). getBlockingQueue(int maxQueueSize)有一个if (maxQueueSize <= 0) return new SynchronousQueue<Runnable>();
     *           判断,如果使用SynchronousQueue是没有排队的,一个请求过来就会创建一个新的线程,如果没有线程可用,则会去创建更多的线程
     *      (2). 如果不是-1 则会创建一个LinkedBlockingQueue<Runnable>(maxQueueSize)
     *          此时会这样,优先使用10个线程来处理请求,如果线程池满了,此时就会在队列里排队,最多可用排10个请求,
     *          如果10个请求都满了,线程池里的10个线程池也满了,还在繁忙中,此时maxsimumsize是10,无法增加新线程,此时就会reject掉
     *    6). new ThreadPoolExecutor(dynamicCoreSize, dynamicCoreSize, keepAliveTime, TimeUnit.MINUTES, workQueue, threadFactory)
     *          这里创建的是默认的线程池配置
     * @param group  groupKey 相当于每个服务共用的一个key , 一般一类服务使用同一线程池
     * @param key commandKey 每个command都拥有一个key , 一个command相当于一个方法
     * @param threadPoolKey 属于哪个线程池的key
     * @param circuitBreaker 短路的配置信息  传入的是null
     * @param threadPool 线程池的配置  传入的是null
     * @param commandPropertiesDefaults 服务的默认配置
     * @param threadPoolPropertiesDefaults 线程的默认配置
     * @param metrics 传入的是null
     * @param fallbackSemaphore 信号量回调信息配置  传入的是null
     * @param executionSemaphore 信号量的配置信息 传入的是null
     * @param propertiesStrategy 传入的是null
     * @param executionHook 传入的是null
     * @Author: zeryts
     * @email: hezitao@agree.com
     * @Date: 2021/4/27 10:44
     */
    protected AbstractCommand(HystrixCommandGroupKey group, HystrixCommandKey key, HystrixThreadPoolKey threadPoolKey, HystrixCircuitBreaker circuitBreaker, HystrixThreadPool threadPool,
            HystrixCommandProperties.Setter commandPropertiesDefaults, HystrixThreadPoolProperties.Setter threadPoolPropertiesDefaults,
            HystrixCommandMetrics metrics, TryableSemaphore fallbackSemaphore, TryableSemaphore executionSemaphore,
            HystrixPropertiesStrategy propertiesStrategy, HystrixCommandExecutionHook executionHook) {


        this.commandGroup = initGroupKey(group);
        this.commandKey = initCommandKey(key, getClass());
        this.properties = initCommandProperties(this.commandKey, propertiesStrategy, commandPropertiesDefaults);
        this.threadPoolKey = initThreadPoolKey(threadPoolKey, this.commandGroup, this.properties.executionIsolationThreadPoolKeyOverride().get());
        this.metrics = initMetrics(metrics, this.commandGroup, this.threadPoolKey, this.commandKey, this.properties);
        this.circuitBreaker = initCircuitBreaker(this.properties.circuitBreakerEnabled().get(), circuitBreaker, this.commandGroup, this.commandKey, this.properties, this.metrics);
        this.threadPool = initThreadPool(threadPool, this.threadPoolKey, threadPoolPropertiesDefaults);

        //Strategies from plugins
        this.eventNotifier = HystrixPlugins.getInstance().getEventNotifier();
        this.concurrencyStrategy = HystrixPlugins.getInstance().getConcurrencyStrategy();
        HystrixMetricsPublisherFactory.createOrRetrievePublisherForCommand(this.commandKey, this.commandGroup, this.metrics, this.circuitBreaker, this.properties);
        this.executionHook = initExecutionHook(executionHook);

        this.requestCache = HystrixRequestCache.getInstance(this.commandKey, this.concurrencyStrategy);
        this.currentRequestLog = initRequestLog(this.properties.requestLogEnabled().get(), this.concurrencyStrategy);

        /* fallback semaphore override if applicable */
        this.fallbackSemaphoreOverride = fallbackSemaphore;

        /* execution semaphore override if applicable */
        this.executionSemaphoreOverride = executionSemaphore;
    }

    private static HystrixCommandGroupKey initGroupKey(final HystrixCommandGroupKey fromConstructor) {
        if (fromConstructor == null) {
            throw new IllegalStateException("HystrixCommandGroup can not be NULL");
        } else {
            return fromConstructor;
        }
    }

    private static HystrixCommandKey initCommandKey(final HystrixCommandKey fromConstructor, Class<?> clazz) {
        if (fromConstructor == null || fromConstructor.name().trim().equals("")) {
            final String keyName = getDefaultNameFromClass(clazz);
            return HystrixCommandKey.Factory.asKey(keyName);
        } else {
            return fromConstructor;
        }
    }

    private static HystrixCommandProperties initCommandProperties(HystrixCommandKey commandKey, HystrixPropertiesStrategy propertiesStrategy, HystrixCommandProperties.Setter commandPropertiesDefaults) {
        if (propertiesStrategy == null) {
            return HystrixPropertiesFactory.getCommandProperties(commandKey, commandPropertiesDefaults);
        } else {
            // used for unit testing
            return propertiesStrategy.getCommandProperties(commandKey, commandPropertiesDefaults);
        }
    }

    /*
     * ThreadPoolKey
     *
     * This defines which thread-pool this command should run on.
     *
     * It uses the HystrixThreadPoolKey if provided, then defaults to use HystrixCommandGroup.
     *
     * It can then be overridden by a property if defined so it can be changed at runtime.
     */
    private static HystrixThreadPoolKey initThreadPoolKey(HystrixThreadPoolKey threadPoolKey, HystrixCommandGroupKey groupKey, String threadPoolKeyOverride) {
        if (threadPoolKeyOverride == null) {
            // we don't have a property overriding the value so use either HystrixThreadPoolKey or HystrixCommandGroup
            if (threadPoolKey == null) {
                /* use HystrixCommandGroup if HystrixThreadPoolKey is null */
                return HystrixThreadPoolKey.Factory.asKey(groupKey.name());
            } else {
                return threadPoolKey;
            }
        } else {
            // we have a property defining the thread-pool so use it instead
            return HystrixThreadPoolKey.Factory.asKey(threadPoolKeyOverride);
        }
    }

    private static HystrixCommandMetrics initMetrics(HystrixCommandMetrics fromConstructor, HystrixCommandGroupKey groupKey,
                                                     HystrixThreadPoolKey threadPoolKey, HystrixCommandKey commandKey,
                                                     HystrixCommandProperties properties) {
        if (fromConstructor == null) {
            return HystrixCommandMetrics.getInstance(commandKey, groupKey, threadPoolKey, properties);
        } else {
            return fromConstructor;
        }
    }

    private static HystrixCircuitBreaker initCircuitBreaker(boolean enabled, HystrixCircuitBreaker fromConstructor,
                                                            HystrixCommandGroupKey groupKey, HystrixCommandKey commandKey,
                                                            HystrixCommandProperties properties, HystrixCommandMetrics metrics) {
        if (enabled) {
            if (fromConstructor == null) {
                // get the default implementation of HystrixCircuitBreaker
                return HystrixCircuitBreaker.Factory.getInstance(commandKey, groupKey, properties, metrics);
            } else {
                return fromConstructor;
            }
        } else {
            return new NoOpCircuitBreaker();
        }
    }

    private static HystrixCommandExecutionHook initExecutionHook(HystrixCommandExecutionHook fromConstructor) {
        if (fromConstructor == null) {
            return new ExecutionHookDeprecationWrapper(HystrixPlugins.getInstance().getCommandExecutionHook());
        } else {
            // used for unit testing
            if (fromConstructor instanceof ExecutionHookDeprecationWrapper) {
                return fromConstructor;
            } else {
                return new ExecutionHookDeprecationWrapper(fromConstructor);
            }
        }
    }

    private static HystrixThreadPool initThreadPool(HystrixThreadPool fromConstructor, HystrixThreadPoolKey threadPoolKey, HystrixThreadPoolProperties.Setter threadPoolPropertiesDefaults) {
        if (fromConstructor == null) {
            // get the default implementation of HystrixThreadPool
            return HystrixThreadPool.Factory.getInstance(threadPoolKey, threadPoolPropertiesDefaults);
        } else {
            return fromConstructor;
        }
    }

    private static HystrixRequestLog initRequestLog(boolean enabled, HystrixConcurrencyStrategy concurrencyStrategy) {
        if (enabled) {
            /* store reference to request log regardless of which thread later hits it */
            return HystrixRequestLog.getCurrentRequest(concurrencyStrategy);
        } else {
            return null;
        }
    }

    /**
     * Allow the Collapser to mark this command instance as being used for a collapsed request and how many requests were collapsed.
     * 
     * @param sizeOfBatch number of commands in request batch
     */
    /* package */void markAsCollapsedCommand(HystrixCollapserKey collapserKey, int sizeOfBatch) {
        eventNotifier.markEvent(HystrixEventType.COLLAPSED, this.commandKey);
        executionResult = executionResult.markCollapsed(collapserKey, sizeOfBatch);
    }

    /**
     * Used for asynchronous execution of command with a callback by subscribing to the {@link Observable}.
     * <p>
     * This eagerly starts execution of the command the same as {@link HystrixCommand#queue()} and {@link HystrixCommand#execute()}.
     * <p>
     * A lazy {@link Observable} can be obtained from {@link #toObservable()}.
     * <p>
     * See https://github.com/Netflix/RxJava/wiki for more information.
     * 
     * @return {@code Observable<R>} that executes and calls back with the result of command execution or a fallback if the command fails for any reason.
     * @throws HystrixRuntimeException
     *             if a fallback does not exist
     *             <p>
     *             <ul>
     *             <li>via {@code Observer#onError} if a failure occurs</li>
     *             <li>or immediately if the command can not be queued (such as short-circuited, thread-pool/semaphore rejected)</li>
     *             </ul>
     * @throws HystrixBadRequestException
     *             via {@code Observer#onError} if invalid arguments or state were used representing a user failure, not a system failure
     * @throws IllegalStateException
     *             if invoked more than once
     */
    public Observable<R> observe() {
        // us a ReplaySubject to buffer the eagerly subscribed-to Observable
        ReplaySubject<R> subject = ReplaySubject.create();
        // eagerly kick off subscription
        final Subscription sourceSubscription = toObservable().subscribe(subject);
        // return the subject that can be subscribed to later while the execution has already started
        return subject.doOnUnsubscribe(new Action0() {
            @Override
            public void call() {
                sourceSubscription.unsubscribe();
            }
        });
    }

    protected abstract Observable<R> getExecutionObservable();

    protected abstract Observable<R> getFallbackObservable();

    /**
     * Used for asynchronous execution of command with a callback by subscribing to the {@link Observable}.
     * <p>
     * This lazily starts execution of the command once the {@link Observable} is subscribed to.
     * <p>
     * An eager {@link Observable} can be obtained from {@link #observe()}.
     * <p>
     * See https://github.com/ReactiveX/RxJava/wiki for more information.
     * 
     * @return {@code Observable<R>} that executes and calls back with the result of command execution or a fallback if the command fails for any reason.
     * @throws HystrixRuntimeException
     *             if a fallback does not exist
     *             <p>
     *             <ul>
     *             <li>via {@code Observer#onError} if a failure occurs</li>
     *             <li>or immediately if the command can not be queued (such as short-circuited, thread-pool/semaphore rejected)</li>
     *             </ul>
     * @throws HystrixBadRequestException
     *             via {@code Observer#onError} if invalid arguments or state were used representing a user failure, not a system failure
     * @throws IllegalStateException
     *             if invoked more than once
     *
     *
     * hystrix几乎所有的核心逻辑，请求缓存、线程池、超时检测、异常检测、熔断触发，都几乎在这个方法里作为入口
     * 1.注解
     *  1) Used for asynchronous execution of command with a callback by subscribing to the {@link Observable}.
     *      这句话的意思是,会将command使用异步的方式来执行,扔到一个线程池里异步去泡,会拿到一个Observable对象,
     *      拿到这个对象后,如果要查看一些状态和结果,需要去订阅这个Observable对象,提供一个回调接口,订阅Observable对象
     *  2) This lazily starts execution of the command once the {@link Observable} is subscribed to
     *      这句话的意思是说,如果你获取了一个Observable对象之后,此时command其实没有立即执行,仅仅是封装到Observable对象里面,什么都没干
     *      如果订阅了Observable对象,提供了回调接口,才会触发Observable内部关联的command会去执行,根据command执行的结果会去回调你提供的接口
     *  3) An eager {@link Observable} can be obtained from {@link #observe()}.
     *      如果你希望一旦获取到Observable对象,就立即执行内部的command,那么就不要调用toObservable方法,要调用observable方法
     * 2. _cmd 其实就是HystrixInvovationHandler里面创建的HystrixCommand的匿名内部类
     * 3. 注解 doOnCompleted handler already did all of the SUCCESS work
     *      如果你的command执行成功之后,会由doOnCompleted()来处理
     *    doOnError handler already did all of the FAILURE/TIMEOUT/REJECTION/BAD_REQUEST work
     *      如果你的command执行过程中,出现了一些异常的情况,如 FAILURE(异常报错) / TIMEOUT(超时) / REJECTION(线程池满,被拒绝) / BAD_REQUEST(错误的请求)
     * 4. Action0 terminateCommandCleanup terminate终止 cleanup收尾
     *     1). 执行成功的回调,如果状态是OBSERVABLE_CHAIN_CREATED,就将状态设置为TERMINAL,同时执行handleCommandEnd(false)方法
     *          然后调用handleCommandEnd方法,将监听的线程给清理掉
     *     2). 如果状态是USER_CODE_EXECUTED , 就将状态设置为TERMINAL,同时执行handleCommandEnd(true)方法
     *          这里代表用户代码已经执行过了
     * 5. Action0 unsubscribeCommandCleanup
     * 6. Func0 applyHystrixSemantics
     * command真正执行的方法
     *  1. executionHook.onStart()  里面啥都没干
     *   executionHook - 构造command的时候传递过来的, 包含一个executionHookDeprecationWrapper,是AbstractCommand的内部类
     *  2. if (circuitBreaker.attemptExecution())
     *    circuitBreaker - 熔断器
     *    1). 尝试去执行,判断是否熔断.如果熔断了就不让你执行command
     * 7. Func1 wrapWithAllOnNextHooks
     * 8. Action0 fireOnCompletedHook
     *  1). threadPool.getScheduler的核心逻辑,就是判断线程池是否已满,如果以满,则会报一个reject的异常
     *      还有的话,如果线程池没满,那么久会去通过线程池进行调度
     *  2). touchConfig(); 默认情况下啥都不干,只有在你设置线程池动态增长的情况下才干活
     *  3). getScheduler()
     *      (1). 这里传入了一个threadPool,把自己传进去 , 这里会创建一个scheduler的一个worker,
     *      (2). 类型为HystrixContextScheduler的worker,里面有判断线程池已满的逻辑
     *          线程池满了之后,会报一个RejectedExecutionException
     *          if (threadPool != null)
     *              if (!threadPool.isQueueSpaceAvailable())
     *                  throw new RejectedExecutionException("Rejected command because thread-pool queueSize is at rejection threshold.");
     *          return worker.schedule(new HystrixContexSchedulerAction(concurrencyStrategy, action), delayTime, unit);
     *      (3) isQueueSpaceAvailable()逻辑为
     *      isQueueSpaceAvailable() {
     *             if (queueSize <= 0) {
     *                 return true;
     *             } else {
     *                 return threadPool.getQueue().size() < properties.queueSizeRejectionThreshold().get();
     *             }
     *  4). new ThreadPoolScheduler(threadPool, shouldInterruptThread) 的createWorker方法会创建一个ThreadPoolWorker
     *      schedule()方法有进行command任务调度的方法
     *  5).    HystrixThreadPoolDefault的getScheduler()方法,
     *      这里传入了一个threadPool,把自己传进去 , 这里会创建一个scheduler的一个worker,
     *             类型为HystrixContextSchedulerWorker的worker,里面有判断线程池已满的逻辑
     *              线程池满了之后,会报一个RejectedExecutionException
     *              if (threadPool != null) {
     *                 if (!threadPool.isQueueSpaceAvailable()) {
     *                     throw new RejectedExecutionException("Rejected command because thread-pool queueSize is at rejection threshold.");
     *                 }
     *              }
     *              return worker.schedule(new HystrixContexSchedulerAction(concurrencyStrategy, action), delayTime, unit);

     * 9. Func0<Observable<R>>()
     *  1). 第一步 :
     *      if (!commandState.compareAndSet(CommandState.NOT_STARTED, CommandState.OBSERVABLE_CHAIN_CREATED))
     *      刚开始做一个command状态的判断,如果说command状态不是NOT_STARTED,他就认为说你已经执行过一次command了,不会让你重复执行一个command
     *      如果说command状态是NOT_STARTED , 则会将状态设置为 OBSERVABLE_CHAIN_CREATED
     *      所以,Func0.call()就是command的入口,Observable.toBlocking()方法出发的
     *  2). 第二步:
     *      if (properties.requestLogEnabled().get())
     *      尝试去处理日志,默认不处理hystrix日志的
     *  3). 第三步:
     *      if (requestCacheEnabled) 检查是否启用了请求缓存,配置项是 requestCache.enabled
     *      默认情况下是不起用的
     *      如果启用的情况,会基于applyHystrixSemantics 和 wrapWithAllOnNextHooks创建
     *      一个HystrixObservable的东西
     *  4). 第四步:
     *  afterCache
     *      .doOnTerminate(terminateCommandCleanup)
     *      .doOnUnsubscribe(unsubscribeCommandCleanup)
     *      .doOnCompleted(fireOnCompletedHook);
     *      基于上面(4,5,6,7,8)所构造的五个对象进行构造 Observable
     * 10. Observable.toBlocking()之后,就会触发 9 中的 Func0.call()方法执行 ,
     *      结果这个call方法中啥也没干,就是再次创建了一个hystrixObservable对象,把之前的五个对象塞进去
     *      所以判断,真正去执行command的方法,估计是在applyHystrixSemantics中去触发的
     */
    public Observable<R> toObservable() {
        /**
         * HystrixInvovationHandler里面创建的HystrixCommand的匿名内部类
         */
        final AbstractCommand<R> _cmd = this;

        //doOnCompleted handler already did all of the SUCCESS work
        //doOnError handler already did all of the FAILURE/TIMEOUT/REJECTION/BAD_REQUEST work
        final Action0 terminateCommandCleanup = new Action0() {

            @Override
            public void call() {
                /**
                 * 这里代表执行成功,将状态改为TERMINAL
                 */
                if (_cmd.commandState.compareAndSet(CommandState.OBSERVABLE_CHAIN_CREATED, CommandState.TERMINAL)) {
                    /**
                     * 这里代表用户代码没执行过
                     */
                    handleCommandEnd(false); //user code never ran
                } else if (_cmd.commandState.compareAndSet(CommandState.USER_CODE_EXECUTED, CommandState.TERMINAL)) {
                    /**
                     * 这里代表用户代码已经执行过
                     */
                    handleCommandEnd(true); //user code did run
                }
            }
        };

        //mark the command as CANCELLED and store the latency (in addition to standard cleanup)
        final Action0 unsubscribeCommandCleanup = new Action0() {
            @Override
            public void call() {
                circuitBreaker.markNonSuccess();
                if (_cmd.commandState.compareAndSet(CommandState.OBSERVABLE_CHAIN_CREATED, CommandState.UNSUBSCRIBED)) {
                    if (!_cmd.executionResult.containsTerminalEvent()) {
                        _cmd.eventNotifier.markEvent(HystrixEventType.CANCELLED, _cmd.commandKey);
                        try {
                            executionHook.onUnsubscribe(_cmd);
                        } catch (Throwable hookEx) {
                            logger.warn("Error calling HystrixCommandExecutionHook.onUnsubscribe", hookEx);
                        }
                        _cmd.executionResultAtTimeOfCancellation = _cmd.executionResult
                                .addEvent((int) (System.currentTimeMillis() - _cmd.commandStartTimestamp), HystrixEventType.CANCELLED);
                    }
                    handleCommandEnd(false); //user code never ran
                } else if (_cmd.commandState.compareAndSet(CommandState.USER_CODE_EXECUTED, CommandState.UNSUBSCRIBED)) {
                    if (!_cmd.executionResult.containsTerminalEvent()) {
                        _cmd.eventNotifier.markEvent(HystrixEventType.CANCELLED, _cmd.commandKey);
                        try {
                            executionHook.onUnsubscribe(_cmd);
                        } catch (Throwable hookEx) {
                            logger.warn("Error calling HystrixCommandExecutionHook.onUnsubscribe", hookEx);
                        }
                        _cmd.executionResultAtTimeOfCancellation = _cmd.executionResult
                                .addEvent((int) (System.currentTimeMillis() - _cmd.commandStartTimestamp), HystrixEventType.CANCELLED);
                    }
                    handleCommandEnd(true); //user code did run
                }
            }
        };

        final Func0<Observable<R>> applyHystrixSemantics = new Func0<Observable<R>>() {
            @Override
            public Observable<R> call() {
                if (commandState.get().equals(CommandState.UNSUBSCRIBED)) {
                    return Observable.never();
                }
                return applyHystrixSemantics(_cmd);
            }
        };

        final Func1<R, R> wrapWithAllOnNextHooks = new Func1<R, R>() {
            @Override
            public R call(R r) {
                R afterFirstApplication = r;

                try {
                    afterFirstApplication = executionHook.onComplete(_cmd, r);
                } catch (Throwable hookEx) {
                    logger.warn("Error calling HystrixCommandExecutionHook.onComplete", hookEx);
                }

                try {
                    return executionHook.onEmit(_cmd, afterFirstApplication);
                } catch (Throwable hookEx) {
                    logger.warn("Error calling HystrixCommandExecutionHook.onEmit", hookEx);
                    return afterFirstApplication;
                }
            }
        };

        final Action0 fireOnCompletedHook = new Action0() {
            @Override
            public void call() {
                try {
                    executionHook.onSuccess(_cmd);
                } catch (Throwable hookEx) {
                    logger.warn("Error calling HystrixCommandExecutionHook.onSuccess", hookEx);
                }
            }
        };

        return Observable.defer(new Func0<Observable<R>>() {
            @Override
            public Observable<R> call() {
                 /* this is a stateful object so can only be used once */
                if (!commandState.compareAndSet(CommandState.NOT_STARTED, CommandState.OBSERVABLE_CHAIN_CREATED)) {
                    IllegalStateException ex = new IllegalStateException("This instance can only be executed once. Please instantiate a new instance.");
                    //TODO make a new error type for this
                    throw new HystrixRuntimeException(FailureType.BAD_REQUEST_EXCEPTION, _cmd.getClass(), getLogMessagePrefix() + " command executed multiple times - this is not permitted.", ex, null);
                }

                commandStartTimestamp = System.currentTimeMillis();

                if (properties.requestLogEnabled().get()) {
                    // log this command execution regardless of what happened
                    if (currentRequestLog != null) {
                        currentRequestLog.addExecutedCommand(_cmd);
                    }
                }

                final boolean requestCacheEnabled = isRequestCachingEnabled();
                final String cacheKey = getCacheKey();

                /* try from cache first */
                if (requestCacheEnabled) {
                    HystrixCommandResponseFromCache<R> fromCache = (HystrixCommandResponseFromCache<R>) requestCache.get(cacheKey);
                    if (fromCache != null) {
                        isResponseFromCache = true;
                        return handleRequestCacheHitAndEmitValues(fromCache, _cmd);
                    }
                }

                Observable<R> hystrixObservable =
                        Observable.defer(applyHystrixSemantics)
                                .map(wrapWithAllOnNextHooks);

                Observable<R> afterCache;

                // put in cache
                if (requestCacheEnabled && cacheKey != null) {
                    // wrap it for caching
                    HystrixCachedObservable<R> toCache = HystrixCachedObservable.from(hystrixObservable, _cmd);
                    HystrixCommandResponseFromCache<R> fromCache = (HystrixCommandResponseFromCache<R>) requestCache.putIfAbsent(cacheKey, toCache);
                    if (fromCache != null) {
                        // another thread beat us so we'll use the cached value instead
                        toCache.unsubscribe();
                        isResponseFromCache = true;
                        return handleRequestCacheHitAndEmitValues(fromCache, _cmd);
                    } else {
                        // we just created an ObservableCommand so we cast and return it
                        afterCache = toCache.toObservable();
                    }
                } else {
                    afterCache = hystrixObservable;
                }

                return afterCache
                        .doOnTerminate(terminateCommandCleanup)     // perform cleanup once (either on normal terminal state (this line), or unsubscribe (next line))
                        .doOnUnsubscribe(unsubscribeCommandCleanup) // perform cleanup once
                        .doOnCompleted(fireOnCompletedHook);
            }
        });
    }
    /** description: command真正执行的方法
     * 1. executionHook.onStart()  里面啥都没干
     *  executionHook - 构造command的时候传递过来的, 包含一个executionHookDeprecationWrapper,是AbstractCommand的内部类
     * 2. if (circuitBreaker.attemptExecution())
     *  circuitBreaker - 熔断器
     *  1). 尝试去执行,判断是否熔断.如果熔断了就不让你执行command
     * 3. 判断隔离模式
     *  1). 如果不是为信号量,则什么逻辑都没有
     *  2). 如果不是为信号量,则什么逻辑都没有
     * 4. 构造 Action0
     *  1). 这个call()里面的方法,代表发生了异常发生的回调
     * 5. 构造 Action1
     *   1). 默认情况executionSemaphore.tryAcquire()就是true,代表可以执行的
     *   2). executionResult设置了一个开始执行的执行时间
     *   3). executeCommandAndObserve(_cmd)  观察命令的执行结果, 获取一个userObservable对象
     *      1. executeCommandWithSpecifiedIsolation(_cmd)  返回一个Observable<R>
     *      2. 施加了一个HystrixObservableTimeoutOperator  用于观察timeout的组件
                1.这里定义timerout的超时监听逻辑
     *          1). originalCommand.isCommandTimedOut.compareAndSet(TimedOutStatus.NOT_EXECUTED, TimedOutStatus.TIMED_OUT)
     *              1. 这里代表已经超时了,将状态从NOT_EXECUTED设置为TIMEOUT,并且抛出一个HystrixTimeoutException异常
     *              2. 用于处理超时时间的Observable,其实就是监听我们command执行是否超时的一个监听器,如果command执行超时,那么此时就会回调TimerListener里面的方法
     *              3. 如果在超时时间的时候执行完,执行状态不会变,会成为 COMPLETED , 然后把监听任务clear()掉
     *          2). Reference<TimerListener> tl = HystrixTimer.getInstance().addTimerListener(listener)
     *              (1). HystrixTimer.getInstance() 这里明显是拿到了一个HystrixTimer的东西,这是一个单例的HystrixTimer
     *                  如果超时了,就回调那个监听器
     *              (2). addTimerListener(listener) 将上面所创建的  listener加到这个HystrixTimer的监听器里面
     *                  1. startThreadIfNeeded() 初始化一个AtomicReference<ScheduledExecutor> ,并调用initialize()方法
     *                  2. initialize()
     *                      1). 获取timerCoreSize的线程池大小
     *                      2). 创建timer线程的工厂,timer线程的名字叫 HystrixTimer-0 , 并设置为守护线程
     *                      3). 创建了一个用来进行调度的ScheduledThreadPoolExecutor线程池,默认大小是4
     *              (3). Runnable r
     *                  1. 创建一个线程,调用TimerListener的tick()方法
     *              (4). ScheduledFuture<?> f = executor.get().getThreadPool().
     *                  scheduleAtFixedRate(r, listener.getIntervalTimeInMilliseconds(), listener.getIntervalTimeInMilliseconds(), TimeUnit.MILLISECONDS);
     *                  1. 通过2)-(2)-2-2)中创建的timerPool来调度创建的Runnable r线程,定时调度
     *                  2. intervalTimeInMilliseconds 配置项是 hystrix.command.serviceA#sayHello(Long,String).execution.isolation.thread.timeoutInMilliseconds = 1000ms
     *                      按照默认设置,每隔1000ms就会跑一次这个任务,根据你设置的超时时间来进行调度
     *      3. 获取执行的execution
     *          1). 如果隔离策略是线程池模式,构造一个叫execution的Observable
     *          2). 获取执行的状态
     *              (1). 如果说要执行command了,结果command的state不是OBSERVABLE_CHAIN_CREATED,那么就会说在一个错误的state之下执行command,报错
     *              (2). 如果状态正常,state状态为USER_CODE_EXECUTED
     *              (3). threadPoolKey默认跟groupKey是一样的,一个服务对应一个线程池
     *          3). 获取userObservable
     *              (1). 线程池在执行的时候,状态一定为 NOT_USING_THREAD
     *              (2). endCurrentThreadExecutingCommand = Hystrix.startCurrentThreadExecutingCommand(getCommandKey())
     *                  传入一个commandKey
     *                  压入到一个栈里面去
     *              (3). 三个on方法,更改下状态
     *              (4). getUserExecutionObservable
     *                  (1). getExecutionObservable()方法中中有一个Func0对象,
     *                  里面的call()方法就执行了我们自己写的run()方法,并返回一个userObservable
     *                  (2). 往userObservable中放入ExecutionHookApplication 和 DeprecatedOnRunHookApplication
     *              (5). 将userObservable返回
     *                  如果要让一个Observable去执行的话,必须对这个Observable进行订阅,在这里的话,其实他内部先会搞一个subscribe订阅器出来,
     *                  然后用这个subscriber去订阅userObservable，然后才能触发userObservable的执行
     *          4). 执行具体的HystrixInvocationHandle里面的HystrixCommand.run()方法,具体执行是SynchronousMethodHandler
     *
     * @param _cmd 是一个具体执行的HystrixCommand
     * @return: rx.Observable<R>
     * @Author: zeryts
     * @email: hezitao@agree.com
     * @Date: 2021/4/27 22:19
     */
    private Observable<R> applyHystrixSemantics(final AbstractCommand<R> _cmd) {

        // mark that we're starting execution on the ExecutionHook
        // if this hook throws an exception, then a fast-fail occurs with no fallback.  No state is left inconsistent
        executionHook.onStart(_cmd);

        /* determine if we're allowed to execute */
        if (circuitBreaker.attemptExecution()) {
            /**
             * 1). 判断是否为信号量模式,默认是基于线程池
             * 2). 如果不是为信号量,则什么逻辑都没有
             */
            final TryableSemaphore executionSemaphore = getExecutionSemaphore();
            final AtomicBoolean semaphoreHasBeenReleased = new AtomicBoolean(false);
            final Action0 singleSemaphoreRelease = new Action0() {
                @Override
                public void call() {
                    if (semaphoreHasBeenReleased.compareAndSet(false, true)) {
                        executionSemaphore.release();
                    }
                }
            };
            /**
             * 1). 这个call()里面的方法,代表发生了异常发生的回调
             * 2). 这个commandKey发生了异常发生的回调
             */
            final Action1<Throwable> markExceptionThrown = new Action1<Throwable>() {
                @Override
                public void call(Throwable t) {
                    eventNotifier.markEvent(HystrixEventType.EXCEPTION_THROWN, commandKey);
                }
            };
            /**
             * 1). 默认情况executionSemaphore.tryAcquire()就是true,代表可以执行的
             * 2). executionResult设置了一个开始执行的执行时间
             * 3). executeCommandAndObserve(_cmd)  观察命令的执行结果
             */
            if (executionSemaphore.tryAcquire()) {
                try {
                    /* used to track userThreadExecutionTime */
                    executionResult = executionResult.setInvocationStartTime(System.currentTimeMillis());
                    return executeCommandAndObserve(_cmd)
                            .doOnError(markExceptionThrown)
                            .doOnTerminate(singleSemaphoreRelease)
                            .doOnUnsubscribe(singleSemaphoreRelease);
                } catch (RuntimeException e) {
                    return Observable.error(e);
                }
            } else {
                return handleSemaphoreRejectionViaFallback();
            }
        } else {
            return handleShortCircuitViaFallback();
        }
    }

    abstract protected boolean commandIsScalar();

    /**
     * This decorates "Hystrix" functionality around the run() Observable.
     *
     * @return R
     *
     * 1. executeCommandWithSpecifiedIsolation(_cmd)  返回一个Observable<R>
     * 2. 施加了一个HystrixObservableTimeoutOperator  用于观察timeout的组件
     * 3. 获取执行的execution
     *  1). 如果隔离策略是线程池模式,构造一个叫execution的Observable
     *  2). 获取执行的状态
     *      (1). 如果说要执行command了,结果command的state不是OBSERVABLE_CHAIN_CREATED,那么就会说在一个错误的state之下执行command,报错
     *      (2). 如果状态正常,state状态为USER_CODE_EXECUTED
     *      (3). threadPoolKey默认跟groupKey是一样的,一个服务对应一个线程池
     *  3). 获取userObservable
     *      (1). 线程池在执行的时候,状态一定为 NOT_USING_THREAD
     *      (2). endCurrentThreadExecutingCommand = Hystrix.startCurrentThreadExecutingCommand(getCommandKey())
     *          传入一个commandKey
     *          压入到一个栈里面去
     *      (3). 三个on方法,更改下状态
     *      (4). getUserExecutionObservable
     *          (1). getExecutionObservable()方法中中有一个Func0对象,
     *           里面的call()方法就执行了我们自己写的run()方法,并返回一个userObservable
     *          (2). 往userObservable中放入ExecutionHookApplication 和 DeprecatedOnRunHookApplication
     *       (5). 将userObservable返回
     *          如果要让一个Observable去执行的话,必须对这个Observable进行订阅,在这里的话,其实他内部先会搞一个subscribe订阅器出来,
     *          然后用这个subscriber去订阅userObservable，然后才能触发userObservable的执行
     *  4). 执行具体的HystrixInvocationHandle里面的HystrixCommand.run()方法,具体执行是SynchronousMethodHandler
     */
    private Observable<R> executeCommandAndObserve(final AbstractCommand<R> _cmd) {
        final HystrixRequestContext currentRequestContext = HystrixRequestContext.getContextForCurrentThread();

        final Action1<R> markEmits = new Action1<R>() {
            @Override
            public void call(R r) {
                if (shouldOutputOnNextEvents()) {
                    executionResult = executionResult.addEvent(HystrixEventType.EMIT);
                    eventNotifier.markEvent(HystrixEventType.EMIT, commandKey);
                }
                if (commandIsScalar()) {
                    long latency = System.currentTimeMillis() - executionResult.getStartTimestamp();
                    eventNotifier.markEvent(HystrixEventType.SUCCESS, commandKey);
                    executionResult = executionResult.addEvent((int) latency, HystrixEventType.SUCCESS);
                    eventNotifier.markCommandExecution(getCommandKey(), properties.executionIsolationStrategy().get(), (int) latency, executionResult.getOrderedList());
                    circuitBreaker.markSuccess();
                }
            }
        };

        final Action0 markOnCompleted = new Action0() {
            @Override
            public void call() {
                if (!commandIsScalar()) {
                    long latency = System.currentTimeMillis() - executionResult.getStartTimestamp();
                    eventNotifier.markEvent(HystrixEventType.SUCCESS, commandKey);
                    executionResult = executionResult.addEvent((int) latency, HystrixEventType.SUCCESS);
                    eventNotifier.markCommandExecution(getCommandKey(), properties.executionIsolationStrategy().get(), (int) latency, executionResult.getOrderedList());
                    circuitBreaker.markSuccess();
                }
            }
        };

        final Func1<Throwable, Observable<R>> handleFallback = new Func1<Throwable, Observable<R>>() {
            @Override
            public Observable<R> call(Throwable t) {
                circuitBreaker.markNonSuccess();
                Exception e = getExceptionFromThrowable(t);
                executionResult = executionResult.setExecutionException(e);
                if (e instanceof RejectedExecutionException) {
                    return handleThreadPoolRejectionViaFallback(e);
                } else if (t instanceof HystrixTimeoutException) {
                    return handleTimeoutViaFallback();
                } else if (t instanceof HystrixBadRequestException) {
                    return handleBadRequestByEmittingError(e);
                } else {
                    /*
                     * Treat HystrixBadRequestException from ExecutionHook like a plain HystrixBadRequestException.
                     */
                    if (e instanceof HystrixBadRequestException) {
                        eventNotifier.markEvent(HystrixEventType.BAD_REQUEST, commandKey);
                        return Observable.error(e);
                    }

                    return handleFailureViaFallback(e);
                }
            }
        };

        final Action1<Notification<? super R>> setRequestContext = new Action1<Notification<? super R>>() {
            @Override
            public void call(Notification<? super R> rNotification) {
                setRequestContextIfNeeded(currentRequestContext);
            }
        };
        /**
         * 1. executeCommandWithSpecifiedIsolation(_cmd)  返回一个Observable<R>
         * 2. 施加了一个HystrixObservableTimeoutOperator  用于观察timeout的组件
         * 3. 获取执行的execution
         *  1). 如果隔离策略是线程池模式,构造一个叫execution的Observable
         *  2). 获取执行的状态
         *      (1). 如果说要执行command了,结果command的state不是OBSERVABLE_CHAIN_CREATED,那么就会说在一个错误的state之下执行command,报错
         *      (2). 如果状态正常,state状态为USER_CODE_EXECUTED
         *      (3). threadPoolKey默认跟groupKey是一样的,一个服务对应一个线程池
         *  3). 获取userObservable
         *      (1). 线程池在执行的时候,状态一定为 NOT_USING_THREAD
         *      (2). endCurrentThreadExecutingCommand = Hystrix.startCurrentThreadExecutingCommand(getCommandKey())
         *          传入一个commandKey
         *          压入到一个栈里面去
         *      (3). 三个on方法,更改下状态
         *      (4). getUserExecutionObservable
         *          (1). getExecutionObservable()方法中中有一个Func0对象,
         *           里面的call()方法就执行了我们自己写的run()方法,并返回一个userObservable
         *          (2). 往userObservable中放入ExecutionHookApplication 和 DeprecatedOnRunHookApplication
         *       (5). 将userObservable返回
         *          如果要让一个Observable去执行的话,必须对这个Observable进行订阅,在这里的话,其实他内部先会搞一个subscribe订阅器出来,
         *          然后用这个subscriber去订阅userObservable，然后才能触发userObservable的执行
         *  4). 执行具体的HystrixInvocationHandle里面的HystrixCommand.run()方法,具体执行是SynchronousMethodHandler
         */
        Observable<R> execution;
        if (properties.executionTimeoutEnabled().get()) {
            execution = executeCommandWithSpecifiedIsolation(_cmd)
                    .lift(new HystrixObservableTimeoutOperator<R>(_cmd));
        } else {
            execution = executeCommandWithSpecifiedIsolation(_cmd);
        }

        return execution.doOnNext(markEmits)
                .doOnCompleted(markOnCompleted)
                .onErrorResumeNext(handleFallback)
                .doOnEach(setRequestContext);
    }
    /**
     * 1. 如果隔离策略是线程池模式,构造一个叫execution的Observable
     * 2. 获取执行的状态
     *   1). 如果说要执行command了,结果command的state不是OBSERVABLE_CHAIN_CREATED,那么就会说在一个错误的state之下执行command,报错
     *   2). 如果状态正常,state状态为USER_CODE_EXECUTED
     *   3). threadPoolKey默认跟groupKey是一样的,一个服务对应一个线程池
     * 3. 获取userObservable
     *  1). 线程池在执行的时候,状态一定为 NOT_USING_THREAD
     *  2). endCurrentThreadExecutingCommand = Hystrix.startCurrentThreadExecutingCommand(getCommandKey())
     *      传入一个commandKey
     *       压入到一个栈里面去
     *  3). 三个on方法,更改下状态
     *  4). getUserExecutionObservable
     *      (1). getExecutionObservable()方法中中有一个Func0对象,
     *           里面的call()方法就执行了我们自己写的run()方法,并返回一个userObservable
     *      (2). 往userObservable中放入ExecutionHookApplication 和 DeprecatedOnRunHookApplication
     *  5). 将userObservable返回
     *      如果要让一个Observable去执行的话,必须对这个Observable进行订阅,在这里的话,其实他内部先会搞一个subscribe订阅器出来,
     *      然后用这个subscriber去订阅userObservable，然后才能触发userObservable的执行
     */
    private Observable<R> executeCommandWithSpecifiedIsolation(final AbstractCommand<R> _cmd) {
        if (properties.executionIsolationStrategy().get() == ExecutionIsolationStrategy.THREAD) {
            // mark that we are executing in a thread (even if we end up being rejected we still were a THREAD execution and not SEMAPHORE)
            return Observable.defer(new Func0<Observable<R>>() {
                @Override
                public Observable<R> call() {
                    /**
                     * 获取执行的状态
                     * 1). 如果说要执行command了,结果command的state不是OBSERVABLE_CHAIN_CREATED,那么就会说在一个错误的state之下执行command,报错
                     * 2). 如果状态正常,state状态为USER_CODE_EXECUTED
                     * 3). threadPoolKey默认跟groupKey是一样的,一个服务对应一个线程池
                     */
                    executionResult = executionResult.setExecutionOccurred();
                    if (!commandState.compareAndSet(CommandState.OBSERVABLE_CHAIN_CREATED, CommandState.USER_CODE_EXECUTED)) {
                        return Observable.error(new IllegalStateException("execution attempted while in state : " + commandState.get().name()));
                    }

                    metrics.markCommandStart(commandKey, threadPoolKey, ExecutionIsolationStrategy.THREAD);

                    if (isCommandTimedOut.get() == TimedOutStatus.TIMED_OUT) {
                        // the command timed out in the wrapping thread so we will return immediately
                        // and not increment any of the counters below or other such logic
                        return Observable.error(new RuntimeException("timed out before executing run()"));
                    }
                    /**
                     * 1). 线程池在执行的时候,状态一定为 NOT_USING_THREAD
                     * 2). endCurrentThreadExecutingCommand = Hystrix.startCurrentThreadExecutingCommand(getCommandKey())
                     *      传入一个commandKey
                     *      压入到一个栈里面去
                     * 3). 三个on方法,更改下状态
                     * 4). getUserExecutionObservable
                     *  (1). getExecutionObservable()方法中中有一个Func0对象,
                     *      里面的call()方法就执行了我们自己写的run()方法,并返回一个userObservable
                     *  (2). 往userObservable中放入ExecutionHookApplication 和 DeprecatedOnRunHookApplication
                     * 5). 将userObservable返回
                     *      如果要让一个Observable去执行的话,必须对这个Observable进行订阅,在这里的话,其实他内部先会搞一个subscribe订阅器出来,
                     *      然后用这个subscriber去订阅userObservable，然后才能触发userObservable的执行
                     */
                    if (threadState.compareAndSet(ThreadState.NOT_USING_THREAD, ThreadState.STARTED)) {
                        //we have not been unsubscribed, so should proceed
                        HystrixCounters.incrementGlobalConcurrentThreads();
                        threadPool.markThreadExecution();
                        // store the command that is being run
                        endCurrentThreadExecutingCommand = Hystrix.startCurrentThreadExecutingCommand(getCommandKey());
                        executionResult = executionResult.setExecutedInThread();
                        /**
                         * If any of these hooks throw an exception, then it appears as if the actual execution threw an error
                         */
                        try {
                            executionHook.onThreadStart(_cmd);
                            executionHook.onRunStart(_cmd);
                            executionHook.onExecutionStart(_cmd);
                            return getUserExecutionObservable(_cmd);
                        } catch (Throwable ex) {
                            return Observable.error(ex);
                        }
                    } else {
                        //command has already been unsubscribed, so return immediately
                        return Observable.empty();
                    }
                }
            }).doOnTerminate(new Action0() {
                @Override
                public void call() {
                    if (threadState.compareAndSet(ThreadState.STARTED, ThreadState.TERMINAL)) {
                        handleThreadEnd(_cmd);
                    }
                    if (threadState.compareAndSet(ThreadState.NOT_USING_THREAD, ThreadState.TERMINAL)) {
                        //if it was never started and received terminal, then no need to clean up (I don't think this is possible)
                    }
                    //if it was unsubscribed, then other cleanup handled it
                }
            }).doOnUnsubscribe(new Action0() {
                @Override
                public void call() {
                    if (threadState.compareAndSet(ThreadState.STARTED, ThreadState.UNSUBSCRIBED)) {
                        handleThreadEnd(_cmd);
                    }
                    if (threadState.compareAndSet(ThreadState.NOT_USING_THREAD, ThreadState.UNSUBSCRIBED)) {
                        //if it was never started and was cancelled, then no need to clean up
                    }
                    //if it was terminal, then other cleanup handled it
                }
                /**
                 * 1). threadPool.getScheduler的核心逻辑,就是判断线程池是否已满,如果以满,则会报一个reject的异常
                 * 还有的话,如果线程池没满,那么久会去通过线程池进行调度
                 * 2). touchConfig(); 默认情况下啥都不干,只有在你设置线程池动态增长的情况下才干活
                 * 3). getScheduler()
                 *      (1). 这里传入了一个threadPool,把自己传进去 , 这里会创建一个scheduler的一个worker,
                 *      (2). 类型为HystrixContextScheduler的worker,里面有判断线程池已满的逻辑
                 *          线程池满了之后,会报一个RejectedExecutionException
                 *          if (threadPool != null)
                 *              if (!threadPool.isQueueSpaceAvailable())
                 *                  throw new RejectedExecutionException("Rejected command because thread-pool queueSize is at rejection threshold.");
                 *          return worker.schedule(new HystrixContexSchedulerAction(concurrencyStrategy, action), delayTime, unit);
                 *      (3) isQueueSpaceAvailable()逻辑为
                 *      isQueueSpaceAvailable() {
                 *             if (queueSize <= 0) {
                 *                 return true;
                 *             } else {
                 *                 return threadPool.getQueue().size() < properties.queueSizeRejectionThreshold().get();
                 *             }
                 *  4). new ThreadPoolScheduler(threadPool, shouldInterruptThread) 的createWorker方法会创建一个ThreadPoolWorker
                 *      schedule()方法有进行command任务调度的方法
                 *  5).    HystrixThreadPoolDefault的getScheduler()方法,
                 *      这里传入了一个threadPool,把自己传进去 , 这里会创建一个scheduler的一个worker,
                 *             类型为HystrixContextSchedulerWorker的worker,里面有判断线程池已满的逻辑
                 *              线程池满了之后,会报一个RejectedExecutionException
                 *              if (threadPool != null) {
                 *                 if (!threadPool.isQueueSpaceAvailable()) {
                 *                     throw new RejectedExecutionException("Rejected command because thread-pool queueSize is at rejection threshold.");
                 *                 }
                 *              }
                 *              return worker.schedule(new HystrixContexSchedulerAction(concurrencyStrategy, action), delayTime, unit);
                 */
            }).subscribeOn(threadPool.getScheduler(new Func0<Boolean>() {
                @Override
                public Boolean call() {
                    return properties.executionIsolationThreadInterruptOnTimeout().get() && _cmd.isCommandTimedOut.get() == TimedOutStatus.TIMED_OUT;
                }
            }));
        } else {
            return Observable.defer(new Func0<Observable<R>>() {
                @Override
                public Observable<R> call() {
                    executionResult = executionResult.setExecutionOccurred();
                    /**
                     * 1). 将commandState的状态从OBSERVABLE_CHAIN_CREATED修改为USER_CODE_EXECUTED
                     * 2).
                     */
                    if (!commandState.compareAndSet(CommandState.OBSERVABLE_CHAIN_CREATED, CommandState.USER_CODE_EXECUTED)) {
                        return Observable.error(new IllegalStateException("execution attempted while in state : " + commandState.get().name()));
                    }

                    metrics.markCommandStart(commandKey, threadPoolKey, ExecutionIsolationStrategy.SEMAPHORE);
                    // semaphore isolated
                    // store the command that is being run
                    endCurrentThreadExecutingCommand = Hystrix.startCurrentThreadExecutingCommand(getCommandKey());
                    try {
                        executionHook.onRunStart(_cmd);
                        executionHook.onExecutionStart(_cmd);
                        return getUserExecutionObservable(_cmd);  //the getUserExecutionObservable method already wraps sync exceptions, so this shouldn't throw
                    } catch (Throwable ex) {
                        //If the above hooks throw, then use that as the result of the run method
                        return Observable.error(ex);
                    }
                }
            });
        }
    }

    /**
     * Execute <code>getFallback()</code> within protection of a semaphore that limits number of concurrent executions.
     * <p>
     * Fallback implementations shouldn't perform anything that can be blocking, but we protect against it anyways in case someone doesn't abide by the contract.
     * <p>
     * If something in the <code>getFallback()</code> implementation is latent (such as a network call) then the semaphore will cause us to start rejecting requests rather than allowing potentially
     * all threads to pile up and block.
     *
     * @return K
     * @throws UnsupportedOperationException
     *             if getFallback() not implemented
     * @throws HystrixRuntimeException
     *             if getFallback() fails (throws an Exception) or is rejected by the semaphore
     */
    private Observable<R> getFallbackOrThrowException(final AbstractCommand<R> _cmd, final HystrixEventType eventType, final FailureType failureType, final String message, final Exception originalException) {
        final HystrixRequestContext requestContext = HystrixRequestContext.getContextForCurrentThread();
        long latency = System.currentTimeMillis() - executionResult.getStartTimestamp();
        // record the executionResult
        // do this before executing fallback so it can be queried from within getFallback (see See https://github.com/Netflix/Hystrix/pull/144)
        executionResult = executionResult.addEvent((int) latency, eventType);

        if (isUnrecoverable(originalException)) {
            logger.error("Unrecoverable Error for HystrixCommand so will throw HystrixRuntimeException and not apply fallback. ", originalException);

            /* executionHook for all errors */
            Exception e = wrapWithOnErrorHook(failureType, originalException);
            return Observable.error(new HystrixRuntimeException(failureType, this.getClass(), getLogMessagePrefix() + " " + message + " and encountered unrecoverable error.", e, null));
        } else {
            if (isRecoverableError(originalException)) {
                logger.warn("Recovered from java.lang.Error by serving Hystrix fallback", originalException);
            }

            if (properties.fallbackEnabled().get()) {
                /* fallback behavior is permitted so attempt */

                final Action1<Notification<? super R>> setRequestContext = new Action1<Notification<? super R>>() {
                    @Override
                    public void call(Notification<? super R> rNotification) {
                        setRequestContextIfNeeded(requestContext);
                    }
                };

                final Action1<R> markFallbackEmit = new Action1<R>() {
                    @Override
                    public void call(R r) {
                        if (shouldOutputOnNextEvents()) {
                            executionResult = executionResult.addEvent(HystrixEventType.FALLBACK_EMIT);
                            eventNotifier.markEvent(HystrixEventType.FALLBACK_EMIT, commandKey);
                        }
                    }
                };

                final Action0 markFallbackCompleted = new Action0() {
                    @Override
                    public void call() {
                        long latency = System.currentTimeMillis() - executionResult.getStartTimestamp();
                        eventNotifier.markEvent(HystrixEventType.FALLBACK_SUCCESS, commandKey);
                        executionResult = executionResult.addEvent((int) latency, HystrixEventType.FALLBACK_SUCCESS);
                    }
                };

                final Func1<Throwable, Observable<R>> handleFallbackError = new Func1<Throwable, Observable<R>>() {
                    @Override
                    public Observable<R> call(Throwable t) {
                        /* executionHook for all errors */
                        Exception e = wrapWithOnErrorHook(failureType, originalException);
                        Exception fe = getExceptionFromThrowable(t);

                        long latency = System.currentTimeMillis() - executionResult.getStartTimestamp();
                        Exception toEmit;

                        if (fe instanceof UnsupportedOperationException) {
                            logger.debug("No fallback for HystrixCommand. ", fe); // debug only since we're throwing the exception and someone higher will do something with it
                            eventNotifier.markEvent(HystrixEventType.FALLBACK_MISSING, commandKey);
                            executionResult = executionResult.addEvent((int) latency, HystrixEventType.FALLBACK_MISSING);

                            toEmit = new HystrixRuntimeException(failureType, _cmd.getClass(), getLogMessagePrefix() + " " + message + " and no fallback available.", e, fe);
                        } else {
                            logger.debug("HystrixCommand execution " + failureType.name() + " and fallback failed.", fe);
                            eventNotifier.markEvent(HystrixEventType.FALLBACK_FAILURE, commandKey);
                            executionResult = executionResult.addEvent((int) latency, HystrixEventType.FALLBACK_FAILURE);

                            toEmit = new HystrixRuntimeException(failureType, _cmd.getClass(), getLogMessagePrefix() + " " + message + " and fallback failed.", e, fe);
                        }

                        // NOTE: we're suppressing fallback exception here
                        if (shouldNotBeWrapped(originalException)) {
                            return Observable.error(e);
                        }

                        return Observable.error(toEmit);
                    }
                };

                final TryableSemaphore fallbackSemaphore = getFallbackSemaphore();
                final AtomicBoolean semaphoreHasBeenReleased = new AtomicBoolean(false);
                final Action0 singleSemaphoreRelease = new Action0() {
                    @Override
                    public void call() {
                        if (semaphoreHasBeenReleased.compareAndSet(false, true)) {
                            fallbackSemaphore.release();
                        }
                    }
                };

                Observable<R> fallbackExecutionChain;

                // acquire a permit
                if (fallbackSemaphore.tryAcquire()) {
                    try {
                        if (isFallbackUserDefined()) {
                            executionHook.onFallbackStart(this);
                            fallbackExecutionChain = getFallbackObservable();
                        } else {
                            //same logic as above without the hook invocation
                            fallbackExecutionChain = getFallbackObservable();
                        }
                    } catch (Throwable ex) {
                        //If hook or user-fallback throws, then use that as the result of the fallback lookup
                        fallbackExecutionChain = Observable.error(ex);
                    }

                    return fallbackExecutionChain
                            .doOnEach(setRequestContext)
                            .lift(new FallbackHookApplication(_cmd))
                            .lift(new DeprecatedOnFallbackHookApplication(_cmd))
                            .doOnNext(markFallbackEmit)
                            .doOnCompleted(markFallbackCompleted)
                            .onErrorResumeNext(handleFallbackError)
                            .doOnTerminate(singleSemaphoreRelease)
                            .doOnUnsubscribe(singleSemaphoreRelease);
                } else {
                   return handleFallbackRejectionByEmittingError();
                }
            } else {
                return handleFallbackDisabledByEmittingError(originalException, failureType, message);
            }
        }
    }

    private Observable<R> getUserExecutionObservable(final AbstractCommand<R> _cmd) {
        Observable<R> userObservable;

        try {
            userObservable = getExecutionObservable();
        } catch (Throwable ex) {
            // the run() method is a user provided implementation so can throw instead of using Observable.onError
            // so we catch it here and turn it into Observable.error
            userObservable = Observable.error(ex);
        }

        return userObservable
                .lift(new ExecutionHookApplication(_cmd))
                .lift(new DeprecatedOnRunHookApplication(_cmd));
    }

    private Observable<R> handleRequestCacheHitAndEmitValues(final HystrixCommandResponseFromCache<R> fromCache, final AbstractCommand<R> _cmd) {
        try {
            executionHook.onCacheHit(this);
        } catch (Throwable hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onCacheHit", hookEx);
        }

        return fromCache.toObservableWithStateCopiedInto(this)
                .doOnTerminate(new Action0() {
                    @Override
                    public void call() {
                        if (commandState.compareAndSet(CommandState.OBSERVABLE_CHAIN_CREATED, CommandState.TERMINAL)) {
                            cleanUpAfterResponseFromCache(false); //user code never ran
                        } else if (commandState.compareAndSet(CommandState.USER_CODE_EXECUTED, CommandState.TERMINAL)) {
                            cleanUpAfterResponseFromCache(true); //user code did run
                        }
                    }
                })
                .doOnUnsubscribe(new Action0() {
                    @Override
                    public void call() {
                        if (commandState.compareAndSet(CommandState.OBSERVABLE_CHAIN_CREATED, CommandState.UNSUBSCRIBED)) {
                            cleanUpAfterResponseFromCache(false); //user code never ran
                        } else if (commandState.compareAndSet(CommandState.USER_CODE_EXECUTED, CommandState.UNSUBSCRIBED)) {
                            cleanUpAfterResponseFromCache(true); //user code did run
                        }
                    }
                });
    }

    private void cleanUpAfterResponseFromCache(boolean commandExecutionStarted) {
        Reference<TimerListener> tl = timeoutTimer.get();
        if (tl != null) {
            tl.clear();
        }

        final long latency = System.currentTimeMillis() - commandStartTimestamp;
        executionResult = executionResult
                .addEvent(-1, HystrixEventType.RESPONSE_FROM_CACHE)
                .markUserThreadCompletion(latency)
                .setNotExecutedInThread();
        ExecutionResult cacheOnlyForMetrics = ExecutionResult.from(HystrixEventType.RESPONSE_FROM_CACHE)
                .markUserThreadCompletion(latency);
        metrics.markCommandDone(cacheOnlyForMetrics, commandKey, threadPoolKey, commandExecutionStarted);
        eventNotifier.markEvent(HystrixEventType.RESPONSE_FROM_CACHE, commandKey);
    }

    private void handleCommandEnd(boolean commandExecutionStarted) {
        Reference<TimerListener> tl = timeoutTimer.get();
        if (tl != null) {
            tl.clear();
        }

        long userThreadLatency = System.currentTimeMillis() - commandStartTimestamp;
        executionResult = executionResult.markUserThreadCompletion((int) userThreadLatency);
        if (executionResultAtTimeOfCancellation == null) {
            metrics.markCommandDone(executionResult, commandKey, threadPoolKey, commandExecutionStarted);
        } else {
            metrics.markCommandDone(executionResultAtTimeOfCancellation, commandKey, threadPoolKey, commandExecutionStarted);
        }

        if (endCurrentThreadExecutingCommand != null) {
            endCurrentThreadExecutingCommand.call();
        }
    }

    private Observable<R> handleSemaphoreRejectionViaFallback() {
        Exception semaphoreRejectionException = new RuntimeException("could not acquire a semaphore for execution");
        executionResult = executionResult.setExecutionException(semaphoreRejectionException);
        eventNotifier.markEvent(HystrixEventType.SEMAPHORE_REJECTED, commandKey);
        logger.debug("HystrixCommand Execution Rejection by Semaphore."); // debug only since we're throwing the exception and someone higher will do something with it
        // retrieve a fallback or throw an exception if no fallback available
        return getFallbackOrThrowException(this, HystrixEventType.SEMAPHORE_REJECTED, FailureType.REJECTED_SEMAPHORE_EXECUTION,
                "could not acquire a semaphore for execution", semaphoreRejectionException);
    }

    private Observable<R> handleShortCircuitViaFallback() {
        // record that we are returning a short-circuited fallback
        eventNotifier.markEvent(HystrixEventType.SHORT_CIRCUITED, commandKey);
        // short-circuit and go directly to fallback (or throw an exception if no fallback implemented)
        Exception shortCircuitException = new RuntimeException("Hystrix circuit short-circuited and is OPEN");
        executionResult = executionResult.setExecutionException(shortCircuitException);
        try {
            return getFallbackOrThrowException(this, HystrixEventType.SHORT_CIRCUITED, FailureType.SHORTCIRCUIT,
                    "short-circuited", shortCircuitException);
        } catch (Exception e) {
            return Observable.error(e);
        }
    }

    private Observable<R> handleThreadPoolRejectionViaFallback(Exception underlying) {
        eventNotifier.markEvent(HystrixEventType.THREAD_POOL_REJECTED, commandKey);
        threadPool.markThreadRejection();
        // use a fallback instead (or throw exception if not implemented)
        return getFallbackOrThrowException(this, HystrixEventType.THREAD_POOL_REJECTED, FailureType.REJECTED_THREAD_EXECUTION, "could not be queued for execution", underlying);
    }

    private Observable<R> handleTimeoutViaFallback() {
        return getFallbackOrThrowException(this, HystrixEventType.TIMEOUT, FailureType.TIMEOUT, "timed-out", new TimeoutException());
    }

    private Observable<R> handleBadRequestByEmittingError(Exception underlying) {
        Exception toEmit = underlying;

        try {
            long executionLatency = System.currentTimeMillis() - executionResult.getStartTimestamp();
            eventNotifier.markEvent(HystrixEventType.BAD_REQUEST, commandKey);
            executionResult = executionResult.addEvent((int) executionLatency, HystrixEventType.BAD_REQUEST);
            Exception decorated = executionHook.onError(this, FailureType.BAD_REQUEST_EXCEPTION, underlying);

            if (decorated instanceof HystrixBadRequestException) {
                toEmit = decorated;
            } else {
                logger.warn("ExecutionHook.onError returned an exception that was not an instance of HystrixBadRequestException so will be ignored.", decorated);
            }
        } catch (Exception hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onError", hookEx);
        }
        /*
         * HystrixBadRequestException is treated differently and allowed to propagate without any stats tracking or fallback logic
         */
        return Observable.error(toEmit);
    }

    private Observable<R> handleFailureViaFallback(Exception underlying) {
        /**
         * All other error handling
         */
        logger.debug("Error executing HystrixCommand.run(). Proceeding to fallback logic ...", underlying);

        // report failure
        eventNotifier.markEvent(HystrixEventType.FAILURE, commandKey);

        // record the exception
        executionResult = executionResult.setException(underlying);
        return getFallbackOrThrowException(this, HystrixEventType.FAILURE, FailureType.COMMAND_EXCEPTION, "failed", underlying);
    }

    private Observable<R> handleFallbackRejectionByEmittingError() {
        long latencyWithFallback = System.currentTimeMillis() - executionResult.getStartTimestamp();
        eventNotifier.markEvent(HystrixEventType.FALLBACK_REJECTION, commandKey);
        executionResult = executionResult.addEvent((int) latencyWithFallback, HystrixEventType.FALLBACK_REJECTION);
        logger.debug("HystrixCommand Fallback Rejection."); // debug only since we're throwing the exception and someone higher will do something with it
        // if we couldn't acquire a permit, we "fail fast" by throwing an exception
        return Observable.error(new HystrixRuntimeException(FailureType.REJECTED_SEMAPHORE_FALLBACK, this.getClass(), getLogMessagePrefix() + " fallback execution rejected.", null, null));
    }

    private Observable<R> handleFallbackDisabledByEmittingError(Exception underlying, FailureType failureType, String message) {
        /* fallback is disabled so throw HystrixRuntimeException */
        logger.debug("Fallback disabled for HystrixCommand so will throw HystrixRuntimeException. ", underlying); // debug only since we're throwing the exception and someone higher will do something with it
        eventNotifier.markEvent(HystrixEventType.FALLBACK_DISABLED, commandKey);

        /* executionHook for all errors */
        Exception wrapped = wrapWithOnErrorHook(failureType, underlying);
        return Observable.error(new HystrixRuntimeException(failureType, this.getClass(), getLogMessagePrefix() + " " + message + " and fallback disabled.", wrapped, null));
    }

    protected boolean shouldNotBeWrapped(Throwable underlying) {
        return underlying instanceof ExceptionNotWrappedByHystrix;
    }

    /**
     * Returns true iff the t was caused by a java.lang.Error that is unrecoverable.  Note: not all java.lang.Errors are unrecoverable.
     * @see <a href="https://github.com/Netflix/Hystrix/issues/713"></a> for more context
     * Solution taken from <a href="https://github.com/ReactiveX/RxJava/issues/748"></a>
     *
     * The specific set of Error that are considered unrecoverable are:
     * <ul>
     * <li>{@code StackOverflowError}</li>
     * <li>{@code VirtualMachineError}</li>
     * <li>{@code ThreadDeath}</li>
     * <li>{@code LinkageError}</li>
     * </ul>
     *
     * @param t throwable to check
     * @return true iff the t was caused by a java.lang.Error that is unrecoverable
     */
    private boolean isUnrecoverable(Throwable t) {
        if (t != null && t.getCause() != null) {
            Throwable cause = t.getCause();
            if (cause instanceof StackOverflowError) {
                return true;
            } else if (cause instanceof VirtualMachineError) {
                return true;
            } else if (cause instanceof ThreadDeath) {
                return true;
            } else if (cause instanceof LinkageError) {
                return true;
            }
        }
        return false;
    }

    private boolean isRecoverableError(Throwable t) {
        if (t != null && t.getCause() != null) {
            Throwable cause = t.getCause();
            if (cause instanceof java.lang.Error) {
                return !isUnrecoverable(t);
            }
        }
        return false;
    }

    protected void handleThreadEnd(AbstractCommand<R> _cmd) {
        HystrixCounters.decrementGlobalConcurrentThreads();
        threadPool.markThreadCompletion();
        try {
            executionHook.onThreadComplete(_cmd);
        } catch (Throwable hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onThreadComplete", hookEx);
        }
    }

    /**
     *
     * @return if onNext events should be reported on
     * This affects {@link HystrixRequestLog}, and {@link HystrixEventNotifier} currently.
     */
    protected boolean shouldOutputOnNextEvents() {
        return false;
    }

    /**
     * 1.这里定义timerout的超时监听逻辑
     *      1). originalCommand.isCommandTimedOut.compareAndSet(TimedOutStatus.NOT_EXECUTED, TimedOutStatus.TIMED_OUT)
     *          这里代表已经超时了,将状态从NOT_EXECUTED设置为TIMEOUT,并且抛出一个HystrixTimeoutException异常
     *          用于处理超时时间的Observable,其实就是监听我们command执行是否超时的一个监听器,
     *          如果command执行超时,那么此时就会回调TimerListener里面的方法
     *      2). Reference<TimerListener> tl = HystrixTimer.getInstance().addTimerListener(listener)
     *          (1). HystrixTimer.getInstance() 这里明显是拿到了一个HystrixTimer的东西,这是一个单例的HystrixTimer
     *              如果超时了,就回调那个监听器
     *          (2). addTimerListener(listener) 将上面所创建的  listener加到这个HystrixTimer的监听器里面
     *              1. startThreadIfNeeded() 初始化一个AtomicReference<ScheduledExecutor> ,并调用initialize()方法
     *              2. initialize()
     *                  1). 获取timerCoreSize的线程池大小
     *                  2). 创建timer线程的工厂,timer线程的名字叫 HystrixTimer-0 , 并设置为守护线程
     *                  3). 创建了一个用来进行调度的ScheduledThreadPoolExecutor线程池,默认大小是4
     *          (3). Runnable r
     *              1. 创建一个线程,调用TimerListener的tick()方法
     *          (4). ScheduledFuture<?> f = executor.get().getThreadPool().
     *              scheduleAtFixedRate(r, listener.getIntervalTimeInMilliseconds(), listener.getIntervalTimeInMilliseconds(), TimeUnit.MILLISECONDS);
     *              1. 通过2)-(2)-2-2)中创建的timerPool来调度创建的Runnable r线程,定时调度
     *              2. intervalTimeInMilliseconds 配置项是 hystrix.command.serviceA#sayHello(Long,String).execution.isolation.thread.timeoutInMilliseconds = 1000ms
     *                  按照默认设置,每隔1000ms就会跑一次这个任务,根据你设置的超时时间来进行调度
     *
     */
    private static class HystrixObservableTimeoutOperator<R> implements Operator<R, R> {

        final AbstractCommand<R> originalCommand;

        public HystrixObservableTimeoutOperator(final AbstractCommand<R> originalCommand) {
            this.originalCommand = originalCommand;
        }

        @Override
        public Subscriber<? super R> call(final Subscriber<? super R> child) {
            final CompositeSubscription s = new CompositeSubscription();
            // if the child unsubscribes we unsubscribe our parent as well
            child.add(s);

            //capture the HystrixRequestContext upfront so that we can use it in the timeout thread later
            final HystrixRequestContext hystrixRequestContext = HystrixRequestContext.getContextForCurrentThread();

            TimerListener listener = new TimerListener() {

                @Override
                public void tick() {
                    // if we can go from NOT_EXECUTED to TIMED_OUT then we do the timeout codepath
                    // otherwise it means we lost a race and the run() execution completed or did not start
                    if (originalCommand.isCommandTimedOut.compareAndSet(TimedOutStatus.NOT_EXECUTED, TimedOutStatus.TIMED_OUT)) {
                        // report timeout failure
                        originalCommand.eventNotifier.markEvent(HystrixEventType.TIMEOUT, originalCommand.commandKey);

                        // shut down the original request
                        s.unsubscribe();

                        final HystrixContextRunnable timeoutRunnable = new HystrixContextRunnable(originalCommand.concurrencyStrategy, hystrixRequestContext, new Runnable() {

                            @Override
                            public void run() {
                                child.onError(new HystrixTimeoutException());
                            }
                        });


                        timeoutRunnable.run();
                        //if it did not start, then we need to mark a command start for concurrency metrics, and then issue the timeout
                    }
                }

                @Override
                public int getIntervalTimeInMilliseconds() {
                    return originalCommand.properties.executionTimeoutInMilliseconds().get();
                }
            };
            // 监听超时的监听器  监听是用一个单例的HystrixTimer()
            final Reference<TimerListener> tl = HystrixTimer.getInstance().addTimerListener(listener);

            // set externally so execute/queue can see this
            originalCommand.timeoutTimer.set(tl);

            /**
             * If this subscriber receives values it means the parent succeeded/completed
             */
            Subscriber<R> parent = new Subscriber<R>() {

                @Override
                public void onCompleted() {
                    if (isNotTimedOut()) {
                        // stop timer and pass notification through
                        tl.clear();
                        child.onCompleted();
                    }
                }

                @Override
                public void onError(Throwable e) {
                    if (isNotTimedOut()) {
                        // stop timer and pass notification through
                        tl.clear();
                        child.onError(e);
                    }
                }

                @Override
                public void onNext(R v) {
                    if (isNotTimedOut()) {
                        child.onNext(v);
                    }
                }

                private boolean isNotTimedOut() {
                    // if already marked COMPLETED (by onNext) or succeeds in setting to COMPLETED
                    return originalCommand.isCommandTimedOut.get() == TimedOutStatus.COMPLETED ||
                            originalCommand.isCommandTimedOut.compareAndSet(TimedOutStatus.NOT_EXECUTED, TimedOutStatus.COMPLETED);
                }

            };

            // if s is unsubscribed we want to unsubscribe the parent
            s.add(parent);

            return parent;
        }

    }

    private static void setRequestContextIfNeeded(final HystrixRequestContext currentRequestContext) {
        if (!HystrixRequestContext.isCurrentThreadInitialized()) {
            // even if the user Observable doesn't have context we want it set for chained operators
            HystrixRequestContext.setContextOnCurrentThread(currentRequestContext);
        }
    }

    /**
     * Get the TryableSemaphore this HystrixCommand should use if a fallback occurs.
     * 
     * @return TryableSemaphore
     */
    protected TryableSemaphore getFallbackSemaphore() {
        if (fallbackSemaphoreOverride == null) {
            TryableSemaphore _s = fallbackSemaphorePerCircuit.get(commandKey.name());
            if (_s == null) {
                // we didn't find one cache so setup
                fallbackSemaphorePerCircuit.putIfAbsent(commandKey.name(), new TryableSemaphoreActual(properties.fallbackIsolationSemaphoreMaxConcurrentRequests()));
                // assign whatever got set (this or another thread)
                return fallbackSemaphorePerCircuit.get(commandKey.name());
            } else {
                return _s;
            }
        } else {
            return fallbackSemaphoreOverride;
        }
    }

    /**
     * Get the TryableSemaphore this HystrixCommand should use for execution if not running in a separate thread.
     * 
     * @return TryableSemaphore
     *
     * 1. 判断是否为信号量模式,默认是基于线程池
     * 2. 如果不是为信号量,则什么逻辑都没有
     *
     */
    protected TryableSemaphore getExecutionSemaphore() {
        if (properties.executionIsolationStrategy().get() == ExecutionIsolationStrategy.SEMAPHORE) {
            if (executionSemaphoreOverride == null) {
                TryableSemaphore _s = executionSemaphorePerCircuit.get(commandKey.name());
                if (_s == null) {
                    // we didn't find one cache so setup
                    executionSemaphorePerCircuit.putIfAbsent(commandKey.name(), new TryableSemaphoreActual(properties.executionIsolationSemaphoreMaxConcurrentRequests()));
                    // assign whatever got set (this or another thread)
                    return executionSemaphorePerCircuit.get(commandKey.name());
                } else {
                    return _s;
                }
            } else {
                return executionSemaphoreOverride;
            }
        } else {
            // return NoOp implementation since we're not using SEMAPHORE isolation
            return TryableSemaphoreNoOp.DEFAULT;
        }
    }

    /**
     * Each concrete implementation of AbstractCommand should return the name of the fallback method as a String
     * This will be used to determine if the fallback "exists" for firing the onFallbackStart/onFallbackError hooks
     * @deprecated This functionality is replaced by {@link #isFallbackUserDefined}, which is less implementation-aware
     * @return method name of fallback
     */
    @Deprecated
    protected abstract String getFallbackMethodName();

    protected abstract boolean isFallbackUserDefined();

    /**
     * @return {@link HystrixCommandGroupKey} used to group together multiple {@link AbstractCommand} objects.
     *         <p>
     *         The {@link HystrixCommandGroupKey} is used to represent a common relationship between commands. For example, a library or team name, the system all related commands interace with,
     *         common business purpose etc.
     */
    public HystrixCommandGroupKey getCommandGroup() {
        return commandGroup;
    }

    /**
     * @return {@link HystrixCommandKey} identifying this command instance for statistics, circuit-breaker, properties, etc.
     */
    public HystrixCommandKey getCommandKey() {
        return commandKey;
    }

    /**
     * @return {@link HystrixThreadPoolKey} identifying which thread-pool this command uses (when configured to run on separate threads via
     *         {@link HystrixCommandProperties#executionIsolationStrategy()}).
     */
    public HystrixThreadPoolKey getThreadPoolKey() {
        return threadPoolKey;
    }

    /* package */HystrixCircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    /**
     * The {@link HystrixCommandMetrics} associated with this {@link AbstractCommand} instance.
     *
     * @return HystrixCommandMetrics
     */
    public HystrixCommandMetrics getMetrics() {
        return metrics;
    }

    /**
     * The {@link HystrixCommandProperties} associated with this {@link AbstractCommand} instance.
     * 
     * @return HystrixCommandProperties
     */
    public HystrixCommandProperties getProperties() {
        return properties;
    }

    /* ******************************************************************************** */
    /* ******************************************************************************** */
    /* Operators that implement hook application */
    /* ******************************************************************************** */
    /* ******************************************************************************** */

    private class ExecutionHookApplication implements Operator<R, R> {
        private final HystrixInvokable<R> cmd;

        ExecutionHookApplication(HystrixInvokable<R> cmd) {
            this.cmd = cmd;
        }

        @Override
        public Subscriber<? super R> call(final Subscriber<? super R> subscriber) {
            return new Subscriber<R>(subscriber) {
                @Override
                public void onCompleted() {
                    try {
                        executionHook.onExecutionSuccess(cmd);
                    } catch (Throwable hookEx) {
                        logger.warn("Error calling HystrixCommandExecutionHook.onExecutionSuccess", hookEx);
                    }
                    subscriber.onCompleted();
                }

                @Override
                public void onError(Throwable e) {
                    Exception wrappedEx = wrapWithOnExecutionErrorHook(e);
                    subscriber.onError(wrappedEx);
                }

                @Override
                public void onNext(R r) {
                    R wrappedValue = wrapWithOnExecutionEmitHook(r);
                    subscriber.onNext(wrappedValue);
                }
            };
        }
    }

    private class FallbackHookApplication implements Operator<R, R> {
        private final HystrixInvokable<R> cmd;

        FallbackHookApplication(HystrixInvokable<R> cmd) {
            this.cmd = cmd;
        }

        @Override
        public Subscriber<? super R> call(final Subscriber<? super R> subscriber) {
            return new Subscriber<R>(subscriber) {
                @Override
                public void onCompleted() {
                    try {
                        executionHook.onFallbackSuccess(cmd);
                    } catch (Throwable hookEx) {
                        logger.warn("Error calling HystrixCommandExecutionHook.onFallbackSuccess", hookEx);
                    }
                    subscriber.onCompleted();
                }

                @Override
                public void onError(Throwable e) {
                    Exception wrappedEx = wrapWithOnFallbackErrorHook(e);
                    subscriber.onError(wrappedEx);
                }

                @Override
                public void onNext(R r) {
                    R wrappedValue = wrapWithOnFallbackEmitHook(r);
                    subscriber.onNext(wrappedValue);
                }
            };
        }
    }

    @Deprecated //separated out to make it cleanly removable
    private class DeprecatedOnRunHookApplication implements Operator<R, R> {

        private final HystrixInvokable<R> cmd;

        DeprecatedOnRunHookApplication(HystrixInvokable<R> cmd) {
            this.cmd = cmd;
        }

        @Override
        public Subscriber<? super R> call(final Subscriber<? super R> subscriber) {
            return new Subscriber<R>(subscriber) {
                @Override
                public void onCompleted() {
                    subscriber.onCompleted();
                }

                @Override
                public void onError(Throwable t) {
                    Exception e = getExceptionFromThrowable(t);
                    try {
                        Exception wrappedEx = executionHook.onRunError(cmd, e);
                        subscriber.onError(wrappedEx);
                    } catch (Throwable hookEx) {
                        logger.warn("Error calling HystrixCommandExecutionHook.onRunError", hookEx);
                        subscriber.onError(e);
                    }
                }

                @Override
                public void onNext(R r) {
                    try {
                        R wrappedValue = executionHook.onRunSuccess(cmd, r);
                        subscriber.onNext(wrappedValue);
                    } catch (Throwable hookEx) {
                        logger.warn("Error calling HystrixCommandExecutionHook.onRunSuccess", hookEx);
                        subscriber.onNext(r);
                    }
                }
            };
        }
    }

    @Deprecated //separated out to make it cleanly removable
    private class DeprecatedOnFallbackHookApplication implements Operator<R, R> {

        private final HystrixInvokable<R> cmd;

        DeprecatedOnFallbackHookApplication(HystrixInvokable<R> cmd) {
            this.cmd = cmd;
        }

        @Override
        public Subscriber<? super R> call(final Subscriber<? super R> subscriber) {
            return new Subscriber<R>(subscriber) {
                @Override
                public void onCompleted() {
                    subscriber.onCompleted();
                }

                @Override
                public void onError(Throwable t) {
                    //no need to call a hook here.  FallbackHookApplication is already calling the proper and non-deprecated hook
                    subscriber.onError(t);
                }

                @Override
                public void onNext(R r) {
                    try {
                        R wrappedValue = executionHook.onFallbackSuccess(cmd, r);
                        subscriber.onNext(wrappedValue);
                    } catch (Throwable hookEx) {
                        logger.warn("Error calling HystrixCommandExecutionHook.onFallbackSuccess", hookEx);
                        subscriber.onNext(r);
                    }
                }
            };
        }
    }

    private Exception wrapWithOnExecutionErrorHook(Throwable t) {
        Exception e = getExceptionFromThrowable(t);
        try {
            return executionHook.onExecutionError(this, e);
        } catch (Throwable hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onExecutionError", hookEx);
            return e;
        }
    }

    private Exception wrapWithOnFallbackErrorHook(Throwable t) {
        Exception e = getExceptionFromThrowable(t);
        try {
            if (isFallbackUserDefined()) {
                return executionHook.onFallbackError(this, e);
            } else {
                return e;
            }
        } catch (Throwable hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onFallbackError", hookEx);
            return e;
        }
    }

    private Exception wrapWithOnErrorHook(FailureType failureType, Throwable t) {
        Exception e = getExceptionFromThrowable(t);
        try {
            return executionHook.onError(this, failureType, e);
        } catch (Throwable hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onError", hookEx);
            return e;
        }
    }

    private R wrapWithOnExecutionEmitHook(R r) {
        try {
            return executionHook.onExecutionEmit(this, r);
        } catch (Throwable hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onExecutionEmit", hookEx);
            return r;
        }
    }

    private R wrapWithOnFallbackEmitHook(R r) {
        try {
            return executionHook.onFallbackEmit(this, r);
        } catch (Throwable hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onFallbackEmit", hookEx);
            return r;
        }
    }

    private R wrapWithOnEmitHook(R r) {
        try {
            return executionHook.onEmit(this, r);
        } catch (Throwable hookEx) {
            logger.warn("Error calling HystrixCommandExecutionHook.onEmit", hookEx);
            return r;
        }
    }

    /**
     * Take an Exception and determine whether to throw it, its cause or a new HystrixRuntimeException.
     * <p>
     * This will only throw an HystrixRuntimeException, HystrixBadRequestException, IllegalStateException
     * or any exception that implements ExceptionNotWrappedByHystrix.
     * 
     * @param e initial exception
     * @return HystrixRuntimeException, HystrixBadRequestException or IllegalStateException
     */
    protected Throwable decomposeException(Exception e) {
        if (e instanceof IllegalStateException) {
            return (IllegalStateException) e;
        }
        if (e instanceof HystrixBadRequestException) {
            if (shouldNotBeWrapped(e.getCause())) {
                return e.getCause();
            }
            return (HystrixBadRequestException) e;
        }
        if (e.getCause() instanceof HystrixBadRequestException) {
            if(shouldNotBeWrapped(e.getCause().getCause())) {
                return e.getCause().getCause();
            }
            return (HystrixBadRequestException) e.getCause();
        }
        if (e instanceof HystrixRuntimeException) {
            return (HystrixRuntimeException) e;
        }
        // if we have an exception we know about we'll throw it directly without the wrapper exception
        if (e.getCause() instanceof HystrixRuntimeException) {
            return (HystrixRuntimeException) e.getCause();
        }
        if (shouldNotBeWrapped(e)) {
            return e;
        }
        if (shouldNotBeWrapped(e.getCause())) {
            return e.getCause();
        }
        // we don't know what kind of exception this is so create a generic message and throw a new HystrixRuntimeException
        String message = getLogMessagePrefix() + " failed while executing.";
        logger.debug(message, e); // debug only since we're throwing the exception and someone higher will do something with it
        return new HystrixRuntimeException(FailureType.COMMAND_EXCEPTION, this.getClass(), message, e, null);

    }

    /* ******************************************************************************** */
    /* ******************************************************************************** */
    /* TryableSemaphore */
    /* ******************************************************************************** */
    /* ******************************************************************************** */

    /**
     * Semaphore that only supports tryAcquire and never blocks and that supports a dynamic permit count.
     * <p>
     * Using AtomicInteger increment/decrement instead of java.util.concurrent.Semaphore since we don't need blocking and need a custom implementation to get the dynamic permit count and since
     * AtomicInteger achieves the same behavior and performance without the more complex implementation of the actual Semaphore class using AbstractQueueSynchronizer.
     */
    /* package */static class TryableSemaphoreActual implements TryableSemaphore {
        protected final HystrixProperty<Integer> numberOfPermits;
        private final AtomicInteger count = new AtomicInteger(0);

        public TryableSemaphoreActual(HystrixProperty<Integer> numberOfPermits) {
            this.numberOfPermits = numberOfPermits;
        }

        @Override
        public boolean tryAcquire() {
            int currentCount = count.incrementAndGet();
            if (currentCount > numberOfPermits.get()) {
                count.decrementAndGet();
                return false;
            } else {
                return true;
            }
        }

        @Override
        public void release() {
            count.decrementAndGet();
        }

        @Override
        public int getNumberOfPermitsUsed() {
            return count.get();
        }

    }

    /* package */static class TryableSemaphoreNoOp implements TryableSemaphore {

        public static final TryableSemaphore DEFAULT = new TryableSemaphoreNoOp();

        @Override
        public boolean tryAcquire() {
            return true;
        }

        @Override
        public void release() {

        }

        @Override
        public int getNumberOfPermitsUsed() {
            return 0;
        }

    }

    /* package */static interface TryableSemaphore {

        /**
         * Use like this:
         * <p>
         * 
         * <pre>
         * if (s.tryAcquire()) {
         * try {
         * // do work that is protected by 's'
         * } finally {
         * s.release();
         * }
         * }
         * </pre>
         * 
         * @return boolean
         */
        public abstract boolean tryAcquire();

        /**
         * ONLY call release if tryAcquire returned true.
         * <p>
         * 
         * <pre>
         * if (s.tryAcquire()) {
         * try {
         * // do work that is protected by 's'
         * } finally {
         * s.release();
         * }
         * }
         * </pre>
         */
        public abstract void release();

        public abstract int getNumberOfPermitsUsed();

    }

    /* ******************************************************************************** */
    /* ******************************************************************************** */
    /* RequestCache */
    /* ******************************************************************************** */
    /* ******************************************************************************** */

    /**
     * Key to be used for request caching.
     * <p>
     * By default this returns null which means "do not cache".
     * <p>
     * To enable caching override this method and return a string key uniquely representing the state of a command instance.
     * <p>
     * If multiple command instances in the same request scope match keys then only the first will be executed and all others returned from cache.
     * 
     * @return cacheKey
     */
    protected String getCacheKey() {
        return null;
    }

    public String getPublicCacheKey() {
        return getCacheKey();
    }

    protected boolean isRequestCachingEnabled() {
        return properties.requestCacheEnabled().get() && getCacheKey() != null;
    }

    protected String getLogMessagePrefix() {
        return getCommandKey().name();
    }

    /**
     * Whether the 'circuit-breaker' is open meaning that <code>execute()</code> will immediately return
     * the <code>getFallback()</code> response and not attempt a HystrixCommand execution.
     *
     * 4 columns are ForcedOpen | ForcedClosed | CircuitBreaker open due to health ||| Expected Result
     *
     * T | T | T ||| OPEN (true)
     * T | T | F ||| OPEN (true)
     * T | F | T ||| OPEN (true)
     * T | F | F ||| OPEN (true)
     * F | T | T ||| CLOSED (false)
     * F | T | F ||| CLOSED (false)
     * F | F | T ||| OPEN (true)
     * F | F | F ||| CLOSED (false)
     *
     * @return boolean
     */
    public boolean isCircuitBreakerOpen() {
        return properties.circuitBreakerForceOpen().get() || (!properties.circuitBreakerForceClosed().get() && circuitBreaker.isOpen());
    }

    /**
     * If this command has completed execution either successfully, via fallback or failure.
     * 
     * @return boolean
     */
    public boolean isExecutionComplete() {
        return commandState.get() == CommandState.TERMINAL;
    }

    /**
     * Whether the execution occurred in a separate thread.
     * <p>
     * This should be called only once execute()/queue()/fireOrForget() are called otherwise it will always return false.
     * <p>
     * This specifies if a thread execution actually occurred, not just if it is configured to be executed in a thread.
     * 
     * @return boolean
     */
    public boolean isExecutedInThread() {
        return getCommandResult().isExecutedInThread();
    }

    /**
     * Whether the response was returned successfully either by executing <code>run()</code> or from cache.
     * 
     * @return boolean
     */
    public boolean isSuccessfulExecution() {
        return getCommandResult().getEventCounts().contains(HystrixEventType.SUCCESS);
    }

    /**
     * Whether the <code>run()</code> resulted in a failure (exception).
     * 
     * @return boolean
     */
    public boolean isFailedExecution() {
        return getCommandResult().getEventCounts().contains(HystrixEventType.FAILURE);
    }

    /**
     * Get the Throwable/Exception thrown that caused the failure.
     * <p>
     * If <code>isFailedExecution() == true</code> then this would represent the Exception thrown by the <code>run()</code> method.
     * <p>
     * If <code>isFailedExecution() == false</code> then this would return null.
     * 
     * @return Throwable or null
     */
    public Throwable getFailedExecutionException() {
        return executionResult.getException();
    }

    /**
     * Get the Throwable/Exception emitted by this command instance prior to checking the fallback.
     * This exception instance may have been generated via a number of mechanisms:
     * 1) failed execution (in this case, same result as {@link #getFailedExecutionException()}.
     * 2) timeout
     * 3) short-circuit
     * 4) rejection
     * 5) bad request
     *
     * If the command execution was successful, then this exception instance is null (there was no exception)
     *
     * Note that the caller of the command may not receive this exception, as fallbacks may be served as a response to
     * the exception.
     *
     * @return Throwable or null
     */
    public Throwable getExecutionException() {
        return executionResult.getExecutionException();
    }

    /**
     * Whether the response received from was the result of some type of failure
     * and <code>getFallback()</code> being called.
     * 
     * @return boolean
     */
    public boolean isResponseFromFallback() {
        return getCommandResult().getEventCounts().contains(HystrixEventType.FALLBACK_SUCCESS);
    }

    /**
     * Whether the response received was the result of a timeout
     * and <code>getFallback()</code> being called.
     * 
     * @return boolean
     */
    public boolean isResponseTimedOut() {
        return getCommandResult().getEventCounts().contains(HystrixEventType.TIMEOUT);
    }

    /**
     * Whether the response received was a fallback as result of being
     * short-circuited (meaning <code>isCircuitBreakerOpen() == true</code>) and <code>getFallback()</code> being called.
     * 
     * @return boolean
     */
    public boolean isResponseShortCircuited() {
        return getCommandResult().getEventCounts().contains(HystrixEventType.SHORT_CIRCUITED);
    }

    /**
     * Whether the response is from cache and <code>run()</code> was not invoked.
     * 
     * @return boolean
     */
    public boolean isResponseFromCache() {
        return isResponseFromCache;
    }

    /**
     * Whether the response received was a fallback as result of being rejected via sempahore
     *
     * @return boolean
     */
    public boolean isResponseSemaphoreRejected() {
        return getCommandResult().isResponseSemaphoreRejected();
    }

    /**
     * Whether the response received was a fallback as result of being rejected via threadpool
     *
     * @return boolean
     */
    public boolean isResponseThreadPoolRejected() {
        return getCommandResult().isResponseThreadPoolRejected();
    }

    /**
     * Whether the response received was a fallback as result of being rejected (either via threadpool or semaphore)
     *
     * @return boolean
     */
    public boolean isResponseRejected() {
        return getCommandResult().isResponseRejected();
    }

    /**
     * List of HystrixCommandEventType enums representing events that occurred during execution.
     * <p>
     * Examples of events are SUCCESS, FAILURE, TIMEOUT, and SHORT_CIRCUITED
     * 
     * @return {@code List<HystrixEventType>}
     */
    public List<HystrixEventType> getExecutionEvents() {
        return getCommandResult().getOrderedList();
    }

    private ExecutionResult getCommandResult() {
        ExecutionResult resultToReturn;
        if (executionResultAtTimeOfCancellation == null) {
            resultToReturn = executionResult;
        } else {
            resultToReturn = executionResultAtTimeOfCancellation;
        }

        if (isResponseFromCache) {
            resultToReturn = resultToReturn.addEvent(HystrixEventType.RESPONSE_FROM_CACHE);
        }

        return resultToReturn;
    }

    /**
     * Number of emissions of the execution of a command.  Only interesting in the streaming case.
     * @return number of <code>OnNext</code> emissions by a streaming command
     */
    @Override
    public int getNumberEmissions() {
        return getCommandResult().getEventCounts().getCount(HystrixEventType.EMIT);
    }

    /**
     * Number of emissions of the execution of a fallback.  Only interesting in the streaming case.
     * @return number of <code>OnNext</code> emissions by a streaming fallback
     */
    @Override
    public int getNumberFallbackEmissions() {
        return getCommandResult().getEventCounts().getCount(HystrixEventType.FALLBACK_EMIT);
    }

    @Override
    public int getNumberCollapsed() {
        return getCommandResult().getEventCounts().getCount(HystrixEventType.COLLAPSED);
    }

    @Override
    public HystrixCollapserKey getOriginatingCollapserKey() {
        return executionResult.getCollapserKey();
    }

    /**
     * The execution time of this command instance in milliseconds, or -1 if not executed.
     * 
     * @return int
     */
    public int getExecutionTimeInMilliseconds() {
        return getCommandResult().getExecutionLatency();
    }

    /**
     * Time in Nanos when this command instance's run method was called, or -1 if not executed 
     * for e.g., command threw an exception
      *
      * @return long
     */
    public long getCommandRunStartTimeInNanos() {
        return executionResult.getCommandRunStartTimeInNanos();
    }

    @Override
    public ExecutionResult.EventCounts getEventCounts() {
        return getCommandResult().getEventCounts();
    }

    protected Exception getExceptionFromThrowable(Throwable t) {
        Exception e;
        if (t instanceof Exception) {
            e = (Exception) t;
        } else {
            // Hystrix 1.x uses Exception, not Throwable so to prevent a breaking change Throwable will be wrapped in Exception
            e = new Exception("Throwable caught while executing.", t);
        }
        return e;
    }

    private static class ExecutionHookDeprecationWrapper extends HystrixCommandExecutionHook {

        private final HystrixCommandExecutionHook actual;

        ExecutionHookDeprecationWrapper(HystrixCommandExecutionHook actual) {
            this.actual = actual;
        }

        @Override
        public <T> T onEmit(HystrixInvokable<T> commandInstance, T value) {
            return actual.onEmit(commandInstance, value);
        }

        @Override
        public <T> void onSuccess(HystrixInvokable<T> commandInstance) {
            actual.onSuccess(commandInstance);
        }

        @Override
        public <T> void onExecutionStart(HystrixInvokable<T> commandInstance) {
            actual.onExecutionStart(commandInstance);
        }

        @Override
        public <T> T onExecutionEmit(HystrixInvokable<T> commandInstance, T value) {
            return actual.onExecutionEmit(commandInstance, value);
        }

        @Override
        public <T> Exception onExecutionError(HystrixInvokable<T> commandInstance, Exception e) {
            return actual.onExecutionError(commandInstance, e);
        }

        @Override
        public <T> void onExecutionSuccess(HystrixInvokable<T> commandInstance) {
            actual.onExecutionSuccess(commandInstance);
        }

        @Override
        public <T> T onFallbackEmit(HystrixInvokable<T> commandInstance, T value) {
            return actual.onFallbackEmit(commandInstance, value);
        }

        @Override
        public <T> void onFallbackSuccess(HystrixInvokable<T> commandInstance) {
            actual.onFallbackSuccess(commandInstance);
        }

        @Override
        @Deprecated
        public <T> void onRunStart(HystrixCommand<T> commandInstance) {
            actual.onRunStart(commandInstance);
        }

        @Override
        public <T> void onRunStart(HystrixInvokable<T> commandInstance) {
            HystrixCommand<T> c = getHystrixCommandFromAbstractIfApplicable(commandInstance);
            if (c != null) {
                onRunStart(c);
            }
            actual.onRunStart(commandInstance);
        }

        @Override
        @Deprecated
        public <T> T onRunSuccess(HystrixCommand<T> commandInstance, T response) {
            return actual.onRunSuccess(commandInstance, response);
        }

        @Override
        @Deprecated
        public <T> T onRunSuccess(HystrixInvokable<T> commandInstance, T response) {
            HystrixCommand<T> c = getHystrixCommandFromAbstractIfApplicable(commandInstance);
            if (c != null) {
                response = onRunSuccess(c, response);
            }
            return actual.onRunSuccess(commandInstance, response);
        }

        @Override
        @Deprecated
        public <T> Exception onRunError(HystrixCommand<T> commandInstance, Exception e) {
            return actual.onRunError(commandInstance, e);
        }

        @Override
        @Deprecated
        public <T> Exception onRunError(HystrixInvokable<T> commandInstance, Exception e) {
            HystrixCommand<T> c = getHystrixCommandFromAbstractIfApplicable(commandInstance);
            if (c != null) {
                e = onRunError(c, e);
            }
            return actual.onRunError(commandInstance, e);
        }

        @Override
        @Deprecated
        public <T> void onFallbackStart(HystrixCommand<T> commandInstance) {
            actual.onFallbackStart(commandInstance);
        }

        @Override
        public <T> void onFallbackStart(HystrixInvokable<T> commandInstance) {
            HystrixCommand<T> c = getHystrixCommandFromAbstractIfApplicable(commandInstance);
            if (c != null) {
                onFallbackStart(c);
            }
            actual.onFallbackStart(commandInstance);
        }

        @Override
        @Deprecated
        public <T> T onFallbackSuccess(HystrixCommand<T> commandInstance, T fallbackResponse) {
            return actual.onFallbackSuccess(commandInstance, fallbackResponse);
        }

        @Override
        @Deprecated
        public <T> T onFallbackSuccess(HystrixInvokable<T> commandInstance, T fallbackResponse) {
            HystrixCommand<T> c = getHystrixCommandFromAbstractIfApplicable(commandInstance);
            if (c != null) {
                fallbackResponse = onFallbackSuccess(c, fallbackResponse);
            }
            return actual.onFallbackSuccess(commandInstance, fallbackResponse);
        }

        @Override
        @Deprecated
        public <T> Exception onFallbackError(HystrixCommand<T> commandInstance, Exception e) {
            return actual.onFallbackError(commandInstance, e);
        }

        @Override
        public <T> Exception onFallbackError(HystrixInvokable<T> commandInstance, Exception e) {
            HystrixCommand<T> c = getHystrixCommandFromAbstractIfApplicable(commandInstance);
            if (c != null) {
                e = onFallbackError(c, e);
            }
            return actual.onFallbackError(commandInstance, e);
        }

        @Override
        @Deprecated
        public <T> void onStart(HystrixCommand<T> commandInstance) {
            actual.onStart(commandInstance);
        }

        @Override
        public <T> void onStart(HystrixInvokable<T> commandInstance) {
            HystrixCommand<T> c = getHystrixCommandFromAbstractIfApplicable(commandInstance);
            if (c != null) {
                onStart(c);
            }
            actual.onStart(commandInstance);
        }

        @Override
        @Deprecated
        public <T> T onComplete(HystrixCommand<T> commandInstance, T response) {
            return actual.onComplete(commandInstance, response);
        }

        @Override
        @Deprecated
        public <T> T onComplete(HystrixInvokable<T> commandInstance, T response) {
            HystrixCommand<T> c = getHystrixCommandFromAbstractIfApplicable(commandInstance);
            if (c != null) {
                response = onComplete(c, response);
            }
            return actual.onComplete(commandInstance, response);
        }

        @Override
        @Deprecated
        public <T> Exception onError(HystrixCommand<T> commandInstance, FailureType failureType, Exception e) {
            return actual.onError(commandInstance, failureType, e);
        }

        @Override
        public <T> Exception onError(HystrixInvokable<T> commandInstance, FailureType failureType, Exception e) {
            HystrixCommand<T> c = getHystrixCommandFromAbstractIfApplicable(commandInstance);
            if (c != null) {
                e = onError(c, failureType, e);
            }
            return actual.onError(commandInstance, failureType, e);
        }

        @Override
        @Deprecated
        public <T> void onThreadStart(HystrixCommand<T> commandInstance) {
            actual.onThreadStart(commandInstance);
        }

        @Override
        public <T> void onThreadStart(HystrixInvokable<T> commandInstance) {
            HystrixCommand<T> c = getHystrixCommandFromAbstractIfApplicable(commandInstance);
            if (c != null) {
                onThreadStart(c);
            }
            actual.onThreadStart(commandInstance);
        }

        @Override
        @Deprecated
        public <T> void onThreadComplete(HystrixCommand<T> commandInstance) {
            actual.onThreadComplete(commandInstance);
        }

        @Override
        public <T> void onThreadComplete(HystrixInvokable<T> commandInstance) {
            HystrixCommand<T> c = getHystrixCommandFromAbstractIfApplicable(commandInstance);
            if (c != null) {
                onThreadComplete(c);
            }
            actual.onThreadComplete(commandInstance);
        }

        @Override
        public <T> void onCacheHit(HystrixInvokable<T> commandInstance) {
            actual.onCacheHit(commandInstance);
        }

        @Override
        public <T> void onUnsubscribe(HystrixInvokable<T> commandInstance) {
            actual.onUnsubscribe(commandInstance);
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        private <T> HystrixCommand<T> getHystrixCommandFromAbstractIfApplicable(HystrixInvokable<T> commandInstance) {
            if (commandInstance instanceof HystrixCommand) {
                return (HystrixCommand) commandInstance;
            } else {
                return null;
            }
        }
    }
}
