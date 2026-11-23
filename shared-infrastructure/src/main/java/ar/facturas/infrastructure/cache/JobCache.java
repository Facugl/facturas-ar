package ar.facturas.infrastructure.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Cache-aside for {@code GET /jobs/{id}}, which the frontend polls.
 *
 * <p>It is the one read that gets hammered: while a comprobante is being
 * processed the client asks for the same job every second or two, and
 * almost every answer is identical to the last one.
 *
 * <h2>Why it is in a shared module</h2>
 *
 * <p>Because the two sides of it are in different processes, and that is
 * the whole reason this is worth building. The API <em>reads</em> the
 * cache; the worker is what makes an entry wrong, because the worker is
 * what finishes the job. So the worker has to invalidate it. In one
 * process this would be a {@code Map.remove} and would demonstrate
 * nothing.
 *
 * <p>The value is an opaque string. The API serializes its own response
 * into it and parses it back out; nothing here knows what a
 * {@code JobResponse} is, which keeps the API's DTO out of a module the
 * worker also depends on. The worker only ever calls
 * {@link #evict(UUID, UUID)}, and does not need to know the shape at all.
 *
 * <h2>Invalidate, never update</h2>
 *
 * <p>{@link #evict(UUID, UUID)} deletes the entry rather than writing the new
 * value into it. Writing it would mean the worker producing the API's
 * response shape — two places building the same payload, free to
 * disagree — and it would also be a lost-update race between the write
 * and whatever the API happens to be caching at that moment. Deleting
 * has neither problem: the next read misses, goes to the database, and
 * caches what it finds.
 *
 * <h2>It degrades, it does not fail</h2>
 *
 * <p>Every method swallows its exception. A cache that takes the request
 * down with it when it is unavailable is worse than no cache: it turns
 * an optional component into a required one. A miss and an error are the
 * same thing to the caller, which is what lets the API keep answering
 * from Postgres while Redis is restarting.
 *
 * <p>The cost of that is a failed <em>eviction</em>, which leaves a stale
 * answer behind — and the TTL is what bounds it. Same shape as the
 * relay's periodic sweep behind its fast path, and the reaper behind the
 * queue: the fast path can fail, and something slower and duller
 * guarantees the outcome. Here the guarantee is only "within the TTL",
 * which is why the TTL is seconds and not minutes.
 */
@Component
@Slf4j
public class JobCache {

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public JobCache(StringRedisTemplate redis,
                    @Value("${facturas.cache.job-ttl}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    /**
     * One place for the key format. Two services build this key, so the
     * day it changes shape it has to change in one file or the API will
     * be reading entries the worker is no longer evicting — a cache bug
     * that looks like a timing bug.
     *
     * <p><strong>The owner is part of the key, and that is not
     * tidiness.</strong> {@code GET /jobs/{id}} answers 404 for a job
     * that belongs to somebody else, and that check runs against the
     * database row. A cache keyed on the job alone would be served
     * <em>before</em> the check ever ran, so one user's polling would
     * fill an entry another user's request would be handed — an
     * authorization bypass built out of a performance optimisation.
     *
     * <p>Keyed this way a request from the wrong user simply misses and
     * falls through to the database, which refuses it properly. The cost
     * is one wasted entry per job, which is nothing: a job has one owner
     * and the other user was never going to be served anyway.
     */
    static String keyFor(UUID userId, UUID jobId) {
        return "job:" + userId + ":" + jobId;
    }

    public Optional<String> get(UUID userId, UUID jobId) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(keyFor(userId, jobId)));
        } catch (RuntimeException e) {
            log.warn("Could not read job {} from the cache; falling through to the database",
                    jobId, e);
            return Optional.empty();
        }
    }

    public void put(UUID userId, UUID jobId, String value) {
        try {
            redis.opsForValue().set(keyFor(userId, jobId), value, ttl);
        } catch (RuntimeException e) {
            log.warn("Could not cache job {}. The answer is already correct; "
                     + "the next read will pay for the database again.", jobId, e);
        }
    }

    /**
     * Called by whoever changed the job — today the worker, after the
     * extraction commits.
     *
     * <p>After the commit, necessarily. Evicting before it would let a
     * read land in the window between the delete and the commit, cache
     * the <em>old</em> row again, and leave the stale answer in place for
     * a full TTL instead of until the next read.
     */
    public void evict(UUID userId, UUID jobId) {
        try {
            redis.delete(keyFor(userId, jobId));
        } catch (RuntimeException e) {
            // The entry stays until its TTL runs out. Worth a warning and
            // not worth failing the extraction that just succeeded.
            log.warn("Could not evict job {} from the cache. The stale entry expires "
                     + "on its own within the TTL.", jobId, e);
        }
    }
}
