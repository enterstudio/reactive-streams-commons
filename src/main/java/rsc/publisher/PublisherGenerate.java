package rsc.publisher;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.*;

import org.reactivestreams.Subscriber;

import rsc.flow.*;
import rsc.util.*;
import rsc.flow.Fuseable.*;

/**
 * Generate signals one-by-one via a function callback.
 * <p>
 * <p>
 * The {@code stateSupplier} may return {@code null} but your {@code stateConsumer} should be prepared to
 * handle it.
 *
 * @param <T> the value type emitted
 * @param <S> the custom state per subscriber
 */
@BackpressureSupport(input = BackpressureMode.NOT_APPLICABLE, output = BackpressureMode.BOUNDED)
@FusionSupport(input = { FusionMode.NOT_APPLICABLE }, output = { FusionMode.SYNC, FusionMode.BOUNDARY })
public final class PublisherGenerate<T, S> 
extends Px<T> {

    /**
     * Interface to receive generated signals from the callback function.
     * <p>
     * Methods of this interface should be called at most once per invocation
     * of the generator function. In addition, at least one of the methods
     * should be called per invocation of the generator function
     *
     * @param <T> the output value type
     */
    public interface PublisherGenerateOutput<T> {

        void onNext(T t);

        void onError(Throwable e);

        void onComplete();

        /**
         * Indicate there won't be any further signals delivered by
         * the generator and the operator will stop calling it.
         * <p>
         * Call to this method will also trigger the state consumer.
         */
        void stop();
    }

    final Callable<S> stateSupplier;

    final BiFunction<S, PublisherGenerateOutput<T>, S> generator;

    final Consumer<? super S> stateConsumer;

    public PublisherGenerate(BiFunction<S, PublisherGenerateOutput<T>, S> generator) {
        this(() -> null, generator, s -> {
        });
    }

    public PublisherGenerate(Callable<S> stateSupplier, BiFunction<S, PublisherGenerateOutput<T>, S> generator) {
        this(stateSupplier, generator, s -> {
        });
    }

    public PublisherGenerate(Callable<S> stateSupplier, BiFunction<S, PublisherGenerateOutput<T>, S> generator,
                             Consumer<? super S> stateConsumer) {
        this.stateSupplier = Objects.requireNonNull(stateSupplier, "stateSupplier");
        this.generator = Objects.requireNonNull(generator, "generator");
        this.stateConsumer = Objects.requireNonNull(stateConsumer, "stateConsumer");
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        S state;

        try {
            state = stateSupplier.call();
        } catch (Throwable e) {
            EmptySubscription.error(s, e);
            return;
        }
        s.onSubscribe(new GenerateSubscription<>(s, state, generator, stateConsumer));
    }

    static final class GenerateSubscription<T, S>
      implements QueueSubscription<T>, PublisherGenerateOutput<T> {

        final Subscriber<? super T> actual;

        final BiFunction<S, PublisherGenerateOutput<T>, S> generator;

        final Consumer<? super S> stateConsumer;

        volatile boolean cancelled;

        S state;

        boolean terminate;

        boolean hasValue;
        
        boolean outputFused;
        
        T generatedValue;
        
        Throwable generatedError;

        volatile long requested;
        @SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<GenerateSubscription> REQUESTED = 
            AtomicLongFieldUpdater.newUpdater(GenerateSubscription.class, "requested");

        public GenerateSubscription(Subscriber<? super T> actual, S state,
                                             BiFunction<S, PublisherGenerateOutput<T>, S> generator, Consumer<? super
          S> stateConsumer) {
            this.actual = actual;
            this.state = state;
            this.generator = generator;
            this.stateConsumer = stateConsumer;
        }

        @Override
        public void onNext(T t) {
            if (terminate) {
                return;
            }
            if (hasValue) {
                onError(new IllegalStateException("More than one call to onNext"));
                return;
            }
            if (t == null) {
                onError(new NullPointerException("The generator produced a null value"));
                return;
            }
            hasValue = true;
            if (outputFused) {
                generatedValue = t;
            } else {
                actual.onNext(t);
            }
        }

        @Override
        public void onError(Throwable e) {
            if (terminate) {
                return;
            }
            terminate = true;
            if (outputFused) {
                generatedError = e;
            } else {
                actual.onError(e);
            }
        }

        @Override
        public void onComplete() {
            if (terminate) {
                return;
            }
            terminate = true;
            if (!outputFused) {
                actual.onComplete();
            }
        }

        @Override
        public void stop() {
            if (terminate) {
                return;
            }
            terminate = true;
        }

        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                if (BackpressureHelper.getAndAddCap(REQUESTED, this, n) == 0) {
                    if (n == Long.MAX_VALUE) {
                        fastPath();
                    } else {
                        slowPath(n);
                    }
                }
            }
        }

        void fastPath() {
            S s = state;

            final BiFunction<S, PublisherGenerateOutput<T>, S> g = generator;

            for (; ; ) {

                if (cancelled) {
                    cleanup(s);
                    return;
                }

                try {
                    s = g.apply(s, this);
                } catch (Throwable e) {
                    cleanup(s);

                    actual.onError(e);
                    return;
                }
                if (terminate || cancelled) {
                    cleanup(s);
                    return;
                }
                if (!hasValue) {
                    cleanup(s);

                    actual.onError(new IllegalStateException("The generator didn't call any of the " +
                      "PublisherGenerateOutput method"));
                    return;
                }

                hasValue = false;
            }
        }

        void slowPath(long n) {
            S s = state;

            long e = 0L;

            final BiFunction<S, PublisherGenerateOutput<T>, S> g = generator;

            for (; ; ) {
                while (e != n) {

                    if (cancelled) {
                        cleanup(s);
                        return;
                    }

                    try {
                        s = g.apply(s, this);
                    } catch (Throwable ex) {
                        cleanup(s);

                        actual.onError(ex);
                        return;
                    }
                    if (terminate || cancelled) {
                        cleanup(s);
                        return;
                    }
                    if (!hasValue) {
                        cleanup(s);

                        actual.onError(new IllegalStateException("The generator didn't call any of the " +
                          "PublisherGenerateOutput method"));
                        return;
                    }

                    e++;
                    hasValue = false;
                }

                n = requested;

                if (n == e) {
                    state = s;
                    n = REQUESTED.addAndGet(this, -e);
                    if (n == 0L) {
                        return;
                    }
                }
            }
        }

        @Override
        public void cancel() {
            if (!cancelled) {
                cancelled = true;

                if (REQUESTED.getAndIncrement(this) == 0) {
                    cleanup(state);
                }
            }
        }

        void cleanup(S s) {
            try {
                state = null;

                stateConsumer.accept(s);
            } catch (Throwable e) {
                UnsignalledExceptions.onErrorDropped(e);
            }
        }
        
        @Override
        public int requestFusion(int requestedMode) {
            if ((requestedMode & Fuseable.SYNC) != 0 && (requestedMode & Fuseable.THREAD_BARRIER) == 0) {
                outputFused = true;
                return Fuseable.SYNC;
            }
            return Fuseable.NONE;
        }
        
        @Override
        public T poll() {
            if (terminate) {
                return null;
            }

            S s = state;
            
            try {
                s = generator.apply(s, this);
            } catch (final Throwable ex) {
                cleanup(s);
                throw ex;
            }
            
            Throwable e = generatedError;
            if (e != null) {
                cleanup(s);
                
                generatedError = null;
                ExceptionHelper.bubble(e);
                return null;
            }
            
            if (!hasValue) {
                cleanup(s);
                
                if (!terminate) {
                    throw new IllegalStateException("The generator didn't call any of the " +
                        "PublisherGenerateOutput method");
                }
                return null;
            }
            
            T v = generatedValue;
            generatedValue = null;
            hasValue = false;

            state = s;
            return v;
        }
        
        @Override
        public boolean isEmpty() {
            return terminate;
        }
        
        @Override
        public int size() {
            return isEmpty() ? 0 : -1;
        }
        
        @Override
        public void clear() {
            generatedError = null;
            generatedValue = null;
        }
    }
}
