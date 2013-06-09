package io.airlift.http.client.testing;

import com.google.common.base.Function;
import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import io.airlift.http.client.AsyncHttpClient;
import io.airlift.http.client.Request;
import io.airlift.http.client.RequestStats;
import io.airlift.http.client.Response;
import io.airlift.http.client.ResponseHandler;
import io.airlift.units.Duration;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class TestingHttpClient
        implements AsyncHttpClient
{
    private final Function<Request, Response> processor;
    private final ListeningExecutorService executor;

    private final RequestStats stats = new RequestStats();
    private final AtomicBoolean closed = new AtomicBoolean();

    public TestingHttpClient(Function<Request, Response> processor)
    {
        this(processor, MoreExecutors.sameThreadExecutor());
    }

    public TestingHttpClient(Function<Request, Response> processor, ExecutorService executor)
    {
        this.processor = processor;
        this.executor = MoreExecutors.listeningDecorator(executor);
    }

    @Override
    public <T, E extends Exception> AsyncHttpResponseFuture<T, E> executeAsync(final Request request, final ResponseHandler<T, E> responseHandler)
    {
        checkNotNull(request, "request is null");
        checkNotNull(responseHandler, "responseHandler is null");
        checkState(!closed.get(), "client is closed");

        final AtomicReference<String> state = new AtomicReference<>("SENDING_REQUEST");
        ListenableFuture<T> future = executor.submit(new Callable<T>()
        {
            @Override
            public T call()
                    throws Exception
            {
                return execute(request, responseHandler, state);
            }
        });

        return new TestingAsyncHttpResponseFuture<>(future, state, request, responseHandler);
    }

    @Override
    public <T, E extends Exception> T execute(Request request, ResponseHandler<T, E> responseHandler)
            throws E
    {
        checkNotNull(request, "request is null");
        checkNotNull(responseHandler, "responseHandler is null");
        checkState(!closed.get(), "client is closed");
        return execute(request, responseHandler, new AtomicReference<>("SENDING_REQUEST"));
    }

    private <T, E extends Exception> T execute(Request request, ResponseHandler<T, E> responseHandler, AtomicReference<String> state)
            throws E
    {
        state.set("PROCESSING_REQUEST");
        Response response;
        Duration requestProcessingTime = null;
        try {
            long requestStart = System.nanoTime();
            response = processor.apply(request);
            requestProcessingTime = Duration.nanosSince(requestStart);
        }
        catch (Throwable e) {
            state.set("FAILED");
            stats.record(request.getMethod(),
                    0,
                    0,
                    0,
                    requestProcessingTime,
                    null);
            if (e instanceof Exception) {
                return responseHandler.handleException(request, (Exception) e);
            } else {
                throw e;
            }
        }
        checkState(response != null, "response is null");

        // notify handler
        state.set("PROCESSING_RESPONSE");
        long responseStart = System.nanoTime();
        try {
            return responseHandler.handle(request, response);
        }
        finally {
            state.set("DONE");
            Duration responseProcessingTime = Duration.nanosSince(responseStart);
            stats.record(request.getMethod(),
                    response.getStatusCode(),
                    response.getBytesRead(),
                    response.getBytesRead(),
                    requestProcessingTime,
                    responseProcessingTime);
        }
    }

    @Override
    public RequestStats getStats()
    {
        return stats;
    }

    @Override
    public void close()
    {
        closed.set(true);
    }

    private class TestingAsyncHttpResponseFuture<T, E extends Exception>
            extends AbstractFuture<T>
            implements AsyncHttpResponseFuture<T, E>
    {
        private final AtomicReference<String> state;
        private final ListenableFuture<T> future;
        private final Request request;
        private final ResponseHandler<T,E> responseHandler;

        private TestingAsyncHttpResponseFuture(ListenableFuture<T> future, AtomicReference<String> state, Request request, ResponseHandler<T, E> responseHandler)
        {
            this.future = future;
            this.state = state;
            this.request = request;
            this.responseHandler = responseHandler;
        }

        @Override
        public String getState()
        {
            return state.get();
        }

        @Override
        public T checkedGet()
                throws E
        {
            try {
                return future.get();
            }
            catch (CancellationException | InterruptedException e) {
                return responseHandler.handleException(request, e);
            }
            catch (ExecutionException e) {
                throw (E) e.getCause();
            }
        }

        @Override
        public T checkedGet(long timeout, TimeUnit unit)
                throws TimeoutException, E
        {
            try {
                return future.get(timeout, unit);
            }
            catch (CancellationException | InterruptedException e) {
                return responseHandler.handleException(request, e);
            }
            catch (ExecutionException e) {
                throw (E) e.getCause();
            }
        }
    }
}
