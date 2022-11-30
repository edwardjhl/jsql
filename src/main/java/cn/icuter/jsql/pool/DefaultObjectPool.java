package cn.icuter.jsql.pool;

import cn.icuter.jsql.datasource.PoolConfiguration;
import cn.icuter.jsql.exception.JSQLException;
import cn.icuter.jsql.exception.PoolException;
import cn.icuter.jsql.exception.PooledObjectCreationException;
import cn.icuter.jsql.exception.PooledObjectPollTimeoutException;
import cn.icuter.jsql.exception.PooledObjectReturnException;
import cn.icuter.jsql.log.JSQLLogger;
import cn.icuter.jsql.log.Logs;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * @author edward
 * @since 2018-08-11
 */
public class DefaultObjectPool<T> implements ObjectPool<T> {

    private static final JSQLLogger LOGGER = Logs.getLogger(DefaultObjectPool.class);

    private static final int IDLE_NEVER_TIMEOUT = -1;
    private static final int IDLE_ALWAYS_TIMEOUT = 0;
    private static final int IDLE_SCHEDULE_OFFSET_MILLISECONDS = 100; // check idle timeout delay 100ms

    private PoolConfiguration poolCfg;
    private final PooledObjectManager<T> manager;
    private final BlockingDeque<PooledObject<T>> idlePooledObjects = new LinkedBlockingDeque<>();
    private Map<Integer, PooledObject<T>> allPooledObjects;

    private final ReentrantLock createLock = new ReentrantLock();
    private final ReadWriteLock poolLock = new ReentrantReadWriteLock();
    private final Lock writeLock = poolLock.writeLock();
    private final Lock readLock = poolLock.readLock();

    private volatile boolean closed;
    private final PoolStats poolStats = new PoolStats();
    private IdleObjectScheduledExecutor idleObjectExecutor;

    public DefaultObjectPool(PooledObjectManager<T> manager) {
        this(manager, PoolConfiguration.defaultPoolCfg());
    }

    public DefaultObjectPool(PooledObjectManager<T> manager,
                             PoolConfiguration poolConfiguration) {
        this.manager = manager;
        initPool(poolConfiguration);

        LOGGER.debug("set up object pool with pool configuration: " + this.poolCfg);
    }

    private void initPool(PoolConfiguration poolConfiguration) {
        this.poolCfg = poolConfiguration;
        if (poolCfg.getMaxPoolSize() <= 0) {
            throw new IllegalArgumentException("max pool size must not be zero!");
        }
        this.allPooledObjects = new ConcurrentHashMap<>(this.poolCfg.getMaxPoolSize());

        long idleObjectTimeout = poolCfg.getIdleTimeout();
        if (idleObjectTimeout > 0) {
            idleObjectExecutor = new IdleObjectScheduledExecutor(1);
            idleObjectExecutor.setThreadFactory(r -> {
                Thread result = new Thread(r);
                result.setName("jsql-object-pool");
                result.setDaemon(true); // terminate while JVM shutdown
                return result;
            });
            if (poolCfg.getScheduledThreadLifeTime() > 0) {
                idleObjectExecutor.setKeepAliveTime(poolCfg.getScheduledThreadLifeTime(), TimeUnit.MILLISECONDS);
                idleObjectExecutor.allowCoreThreadTimeOut(true);
            }
        }
    }

    @Override
    public T borrowObject() throws JSQLException {
        readLock.lock();
        try {
            PooledObject<T> pc = getPooledObject();
            if (pc == null) {
                return null;
            }
            pc.setBorrowed();
            pc.updateLastBorrowedTime();
            poolStats.updateBorrowStats();
            poolStats.updateLastAccessTime();
            return pc.getObject();
        } finally {
            readLock.unlock();
        }
    }

    private PooledObject<T> getPooledObject() throws JSQLException {
        PooledObject<T> pooledObject;
        do {
            if (isPoolClosed()) {
                throw new PoolException("get pooled object fail, due to pool was already closed!");
            }
            pooledObject = idlePooledObjects.pollFirst();
            // queue is empty and pool not full, try to create one
            if (pooledObject == null && poolStats.poolSize < poolCfg.getMaxPoolSize()) {
                createLock.lock();
                try {
                    if (poolStats.poolSize < poolCfg.getMaxPoolSize()) {
                        pooledObject = tryToCreate(poolCfg.getCreateRetryCount());
                        if (pooledObject == null) {
                            throw new PooledObjectCreationException("create pool object error!");
                        }
                        pooledObject.setObjectPool(this);
                        allPooledObjects.put(System.identityHashCode(pooledObject.getObject()), pooledObject);
                        poolStats.updateCreateStats();

                        LOGGER.trace("pooled object has been created, object detail: " + pooledObject);
                        break;
                    }
                } finally {
                    createLock.unlock();
                }
            }
            // pool reach the max size
            if (pooledObject == null) {
                if (poolCfg.getPollTimeout() > 0) {
                    try {
                        pooledObject = idlePooledObjects.pollFirst(poolCfg.getPollTimeout(), TimeUnit.MILLISECONDS);
                        if (pooledObject == null) {
                            throw new PooledObjectPollTimeoutException("get pool object timeout, waited for "
                                    + poolCfg.getPollTimeout() + "ms");
                        }
                    } catch (InterruptedException e) {
                        throw new PoolException("get pool object fail!", e);
                    }
                } else {
                    try {
                        // setting poll timeout args for releasing CPU resource instead of Thread.yield()
                        pooledObject = idlePooledObjects.pollFirst(1000L, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException e) {
                        throw new PoolException("get pool object fail!", e);
                    }
                }
            }
            if (pooledObject != null) {
                // check whether removed by idle object schedule task
                if (!pooledObject.isValid() || validateFailOnBorrow(pooledObject)) {
                    invalidPooledObject(pooledObject);
                    continue;
                }
                break;
            }
            if (isPollNoWait()) {
                return null;
            }
            LOGGER.trace("get pooled object failed, continue to get the next");
        } while (true);
        return pooledObject;
    }

    private boolean isPollNoWait() {
        return poolCfg.getPollTimeout() == 0;
    }

    private boolean validateFailOnBorrow(PooledObject<T> pooledObject) throws JSQLException {
        return poolCfg.isValidateOnBorrow() && !manager.validate(pooledObject);
    }

    private PooledObject<T> tryToCreate(int tryCount) throws JSQLException {
        try {
            return manager.create();
        } catch (JSQLException e) {
            if (tryCount > 0) {
                LOGGER.warn("creating pool object error: " + e.toString()
                        + ", retry to create pool object with try count: " + (poolCfg.getCreateRetryCount() - tryCount + 1));
                return tryToCreate(tryCount - 1);
            } else {
                LOGGER.error("try to create pool object fail when exceeded retrying count: "
                        + poolCfg.getCreateRetryCount());
            }
            throw e;
        }
    }

    private boolean isPoolObjectIdleTimeout(PooledObject<T> pooledObject) {
        return isAlwaysIdleTimeout()
                || !isNeverIdleTimeout() && pooledObject.getLastReturnedTime() > 0
                && System.currentTimeMillis() - pooledObject.getLastReturnedTime() >= poolCfg.getIdleTimeout();
    }

    private boolean isAlwaysIdleTimeout() {
        return poolCfg.getIdleTimeout() == IDLE_ALWAYS_TIMEOUT;
    }

    private boolean isNeverIdleTimeout() {
        return poolCfg.getIdleTimeout() <= IDLE_NEVER_TIMEOUT;
    }

    synchronized boolean isPoolEmpty() {
        return poolStats.poolSize == 0;
    }

    private void invalidPooledObject(PooledObject<T> pooledObject) throws JSQLException {
        if (allPooledObjects.remove(System.identityHashCode(pooledObject.getObject())) != null) {
            manager.invalid(pooledObject);
            pooledObject.setInvalid();
            poolStats.updateRemoveStats();
        }
    }

    @Override
    public void returnObject(T object) throws JSQLException {
        if (object == null) {
            // ignore
            LOGGER.warn("returning object is null, no object will be returned");
            return;
        }
        PooledObject<T> pooledObject = getPooledObject(object);

        Objects.requireNonNull(pooledObject, "no such object in pool!");

        if (!pooledObject.isBorrowed()) {
            throw new PooledObjectReturnException("Object has been returned!");
        }
        poolStats.updateLastAccessTime();
        readLock.lock();
        try {
            if (isPoolClosed() || isAlwaysIdleTimeout() || !pooledObject.isValid() || validateFailOnReturn(pooledObject)) {
                invalidPooledObject(pooledObject);
                removeIdleScheduledTask(pooledObject);
                return;
            }
            pooledObject.setReturned();
            pooledObject.updateLastReturnedTime();
            removeIdleScheduledTask(pooledObject);
            scheduleIdleTimeoutTask(pooledObject);
            idlePooledObjects.addLast(pooledObject);
            poolStats.updateReturnStats();
        } finally {
            readLock.unlock();
        }
    }

    private void removeIdleScheduledTask(PooledObject<T> pooledObject) {
        if (idleObjectExecutor != null) {
            //noinspection ResultOfMethodCallIgnored
            idleObjectExecutor.getQueue().remove(pooledObject.scheduledFuture);
        }
    }

    private boolean validateFailOnReturn(PooledObject<T> pooledObject) throws JSQLException {
        return poolCfg.isValidateOnReturn() && !manager.validate(pooledObject);
    }

    private void scheduleIdleTimeoutTask(PooledObject<T> pooledObject) {
        if (idleObjectExecutor != null) {
            idleObjectExecutor.scheduleIdleTask(new IdleObjectTimeoutTask(pooledObject),
                    poolCfg.getIdleTimeout() + IDLE_SCHEDULE_OFFSET_MILLISECONDS);
        }
    }

    private PooledObject<T> getPooledObject(T object) {
        return allPooledObjects.get(System.identityHashCode(object));
    }

    @Override
    public void close() throws JSQLException {
        if (isPoolClosed()) {
            return;
        }
        writeLock.lock();
        try {
            if (isPoolClosed()) {
                return;
            }
            closed = true;
            if (idleObjectExecutor != null) {
                idleObjectExecutor.shutdownNow();
            }
            PooledObject<T> pooledObject;
            while ((pooledObject = idlePooledObjects.poll()) != null) {
                invalidPooledObject(pooledObject);
            }
        } finally {
            writeLock.unlock();
        }
        LOGGER.debug("succeed in closing object pool, for more info: " + debugInfo());
    }

    @Override
    protected final void finalize() throws Throwable {
        try {
            close();
            // force to invalid all pooled objects
            forceInvalidPooledObjects();
        } finally {
            super.finalize();
        }
    }

    private void forceInvalidPooledObjects() throws Exception {
        for (Map.Entry<Integer, PooledObject<T>> entry : allPooledObjects.entrySet()) {
            invalidPooledObject(entry.getValue());
        }
    }

    boolean isPoolClosed() {
        return closed;
    }

    @Override
    public String debugInfo() {
        return "pool state: " + (closed ? "CLOSED" : "RUNNING") + ", " + poolStats + ", " + poolCfg
                + ", idle schedule service info: "
                + (idleObjectExecutor != null ? idleObjectExecutor.toString() : "NOT RUNNING")
                + ", idle object size: " + idlePooledObjects.size();
    }

    PoolStats getPoolStats() {
        return poolStats;
    }

    class PoolStats {
        volatile long poolSize;
        long createdCnt;
        long invalidCnt;
        long borrowedCnt;
        long returnedCnt;
        volatile long lastAccessTime;
        volatile String formattedLastAccessTime;

        PoolStats() {
            lastAccessTime = System.currentTimeMillis();
        }

        void updateCreateStats() {
            createLock.lock();
            try {
                // noinspection NonAtomicOperationOnVolatileField
                poolSize++;
                createdCnt++;
            } finally {
                createLock.unlock();
            }
        }
        void updateRemoveStats() {
            createLock.lock();
            try {
                // noinspection NonAtomicOperationOnVolatileField
                poolSize--;
                invalidCnt++;
            } finally {
                createLock.unlock();
            }
        }
        synchronized void updateBorrowStats() {
            borrowedCnt++;
        }
        synchronized void updateReturnStats() {
            returnedCnt++;
        }
        void updateLastAccessTime() {
            lastAccessTime = System.currentTimeMillis();
            LocalDateTime localDateTime = new Timestamp(lastAccessTime).toLocalDateTime();
            formattedLastAccessTime = localDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }

        @Override
        public String toString() {
            return "PoolStats {poolSize=" + poolSize
                    + ", createdCnt=" + createdCnt
                    + ", invalidCnt=" + invalidCnt
                    + ", borrowedCnt=" + borrowedCnt
                    + ", returnedCnt=" + returnedCnt
                    + ", lastAccessTime=" + formattedLastAccessTime + "}";
        }
    }

    class IdleObjectScheduledExecutor extends ScheduledThreadPoolExecutor {
        IdleObjectScheduledExecutor(int corePoolSize) {
            super(corePoolSize);
        }
        void scheduleIdleTask(IdleObjectTimeoutTask task, long delay) {
            task.pooledObject.scheduledFuture = (RunnableScheduledFuture) schedule(task, delay, TimeUnit.MILLISECONDS);
        }
    }

    private static final JSQLLogger TASK_LOGGER = Logs.getLogger(DefaultObjectPool.IdleObjectTimeoutTask.class);

    class IdleObjectTimeoutTask implements Runnable {
        private final PooledObject<T> pooledObject;

        IdleObjectTimeoutTask(PooledObject<T> pooledObject) {
            this.pooledObject = pooledObject;
        }

        @Override
        public void run() {
            // If ObjectPool has been closed, that would never run its' scheduled task
            if (isIdleTimeout()) {
                writeLock.lock();
                try {
                    if (isIdleTimeout()) {
                        invalidPooledObject(pooledObject);
                        TASK_LOGGER.debug("invalidate pooled object: " + pooledObject);
                    }
                } catch (JSQLException e) {
                    TASK_LOGGER.error("invalidating pooled object error", e);
                } finally {
                    writeLock.unlock();
                }
            }
        }

        private boolean isIdleTimeout() {
            return pooledObject != null && pooledObject.isValid()
                    && !pooledObject.isBorrowed() && !isPoolClosed() && isPoolObjectIdleTimeout(pooledObject);
        }
    }
}
