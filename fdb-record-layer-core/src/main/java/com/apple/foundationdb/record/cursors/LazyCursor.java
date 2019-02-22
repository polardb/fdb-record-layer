/*
 * LazyCursor.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2015-2018 Apple Inc. and the FoundationDB project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apple.foundationdb.record.cursors;

import com.apple.foundationdb.annotation.API;
import com.apple.foundationdb.record.RecordCoreException;
import com.apple.foundationdb.record.RecordCursor;
import com.apple.foundationdb.record.RecordCursorResult;
import com.apple.foundationdb.record.RecordCursorVisitor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Wraps a future that supplies a record cursor. A typical use case for this cursor will be when the type
 * of cursor that is to be produced by depend on the result of another future.  For example:
 * <pre>
 *    return new LazyCursor(isTheThingReady().thenCompose( isThingReady -&gt; {
 *        if (isThingReady) {
 *            return new KeyValueCursor(...);
 *        } else {
 *            return RecordCursor.empty();
 *        }
 *    } ));
 * </pre>
 * Due to the lazy nature of the cursor, it is impermissable to call any synchronous methods that return information
 * about the state of the cursor until <code>onHasNext()</code> has been called.
 * @param <T> the type of elements of the cursor
 */
@API(API.Status.MAINTAINED)
public class LazyCursor<T> implements RecordCursor<T> {
    @Nonnull
    private final CompletableFuture<RecordCursor<T>> futureCursor;
    @Nullable
    private RecordCursor<T> inner;
    private Executor executor;

    @Nullable
    private CompletableFuture<Boolean> hasNextFuture;
    @Nullable
    private RecordCursorResult<T> nextResult;
    // for detecting incorrect cursor usage
    private boolean mayGetContinuation = false;

    public LazyCursor(@Nonnull CompletableFuture<RecordCursor<T>> futureCursor) {
        this(futureCursor, null);
    }

    /**
     * Creates a new lazy cursor.
     * @param futureCursor the future that will ultimately supply the actual underlying cursor
     * @param executor if not null, then this executor will be returned from {@link #getExecutor()}, otherwise
     *    <code>getExecutor()</code> will throw an exception if called before the underlying cursor has
     *    been materialized. It is advisable to provide this value when chaining cursors in the event that
     *    another wrapping cursor may depend on the executor from this cursor before the cursor is used.
     */
    public LazyCursor(@Nonnull CompletableFuture<RecordCursor<T>> futureCursor, @Nullable Executor executor) {
        this.futureCursor = futureCursor;
        this.executor = executor;
    }

    @Nonnull
    @Override
    @API(API.Status.EXPERIMENTAL)
    public CompletableFuture<RecordCursorResult<T>> onNext() {
        if (inner == null) {
            return futureCursor.thenAccept(cursor -> inner = cursor).thenCompose(vignore -> this.onNext());
        } else {
            return inner.onNext().thenApply(result -> {
                nextResult = result;
                mayGetContinuation = !result.hasNext();
                return result;
            });
        }
    }

    @Nonnull
    @Override
    public CompletableFuture<Boolean> onHasNext() {
        if (hasNextFuture == null) {
            mayGetContinuation = false;
            hasNextFuture = onNext().thenApply(RecordCursorResult::hasNext);
        }
        return hasNextFuture;
    }

    @Nullable
    @Override
    public T next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        mayGetContinuation = true;
        hasNextFuture = null;
        return nextResult.get();
    }

    @Nullable
    @Override
    public byte[] getContinuation() {
        IllegalContinuationAccessChecker.check(mayGetContinuation);
        return nextResult.getContinuation().toBytes();
    }

    @Override
    public NoNextReason getNoNextReason() {
        return nextResult.getNoNextReason();
    }

    @Override
    public void close() {
        if (inner != null) {
            inner.close();
        }
        if (hasNextFuture != null) {
            hasNextFuture.cancel(false);
        }
    }

    @Nonnull
    @Override
    public Executor getExecutor() {
        if (this.executor == null) {
            return getInner().getExecutor();
        }
        return this.executor;
    }

    private RecordCursor<T> getInner() {
        if (inner == null) {
            throw new RecordCoreException("Inner cursor is not available until onHasNext() is called");
        }
        return inner;
    }

    @Override
    public boolean accept(@Nonnull RecordCursorVisitor visitor) {
        if (visitor.visitEnter(this)) {
            getInner().accept(visitor);
        }
        return visitor.visitLeave(this);
    }
}
