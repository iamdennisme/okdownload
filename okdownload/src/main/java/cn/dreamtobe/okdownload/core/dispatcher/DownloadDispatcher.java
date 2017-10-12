/*
 * Copyright (C) 2017 Jacksgong(jacksgong.com)
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

package cn.dreamtobe.okdownload.core.dispatcher;


import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import cn.dreamtobe.okdownload.DownloadTask;
import cn.dreamtobe.okdownload.OkDownload;
import cn.dreamtobe.okdownload.core.Util;
import cn.dreamtobe.okdownload.core.cause.EndCause;
import cn.dreamtobe.okdownload.core.download.DownloadCall;

public class DownloadDispatcher {
    // same id will be discard
    // submit task to download server
    // assemble breakpoint and start chain on block dispatcher

    int maxTaskCount = 15;
    // for sort performance(not need to copy one array), using ArrayList instead of deque(for add
    // on top, remove on bottom).
    private final List<DownloadCall> readyAsyncCalls;

    private final List<DownloadCall> runningAsyncCalls;
    private final List<DownloadCall> runningSyncCalls;

    private @Nullable
    ExecutorService executorService;

    public DownloadDispatcher() {
        this(new ArrayList<DownloadCall>(), new ArrayList<DownloadCall>(),
                new ArrayList<DownloadCall>());
    }

    DownloadDispatcher(List<DownloadCall> readyAsyncCalls, List<DownloadCall> runningAsyncCalls,
                       List<DownloadCall> runningSyncCalls) {
        this.readyAsyncCalls = readyAsyncCalls;
        this.runningAsyncCalls = runningAsyncCalls;
        this.runningSyncCalls = runningSyncCalls;
    }

    synchronized ExecutorService executorService() {
        if (executorService == null) {
            executorService = new ThreadPoolExecutor(0, Integer.MAX_VALUE,
                    60, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(),
                    Util.threadFactory("OkDownload Download", false));
        }

        return executorService;
    }

    public synchronized void enqueue(DownloadTask task) {
        if (inspectForConflict(task)) return;

        final DownloadCall call = DownloadCall.create(task, true);
        if (runningAsyncCalls.size() < maxTaskCount) {
            runningAsyncCalls.add(call);
            executorService().execute(call);
        } else {
            // priority
            readyAsyncCalls.add(call);
            Collections.sort(readyAsyncCalls);
        }
    }

    public synchronized void execute(DownloadTask task) {
        if (inspectForConflict(task)) return;

        final DownloadCall call = DownloadCall.create(task, false);
        runningSyncCalls.add(call);

        syncRunCall(call);
    }

    public synchronized void cancelAll() {
        for (DownloadCall call : readyAsyncCalls) {
            call.cancel();
        }

        for (DownloadCall call : runningAsyncCalls) {
            call.cancel();
        }

        for (DownloadCall call : runningSyncCalls) {
            call.cancel();
        }
    }

    public synchronized boolean cancel(DownloadTask task) {
        for (Iterator<DownloadCall> i = readyAsyncCalls.iterator(); i.hasNext(); ) {
            DownloadCall call = i.next();
            if (call.task == task) {
                // cancel manually from queue.
                i.remove();
                OkDownload.with().callbackDispatcher().dispatch().taskEnd(task, EndCause.CANCELED,
                        null);
                return true;
            }
        }

        for (DownloadCall call : runningAsyncCalls) {
            if (call.task == task) {
                call.cancel();
                return true;
            }
        }

        for (DownloadCall call : runningSyncCalls) {
            if (call.task == task) {
                call.cancel();
                return true;
            }
        }

        return false;
    }

    // this method convenient for unit-test.
    void syncRunCall(DownloadCall call) {
        call.run();
    }

    public synchronized void finish(DownloadCall call) {
        final boolean asyncExecuted = call.asyncExecuted;
        final Collection<DownloadCall> calls = asyncExecuted ? runningAsyncCalls : runningSyncCalls;
        if (!calls.remove(call)) throw new AssertionError("Call wasn't in-flight!");

        if (asyncExecuted) processCalls();
    }

    public synchronized boolean isFileConflictAfterRun(@NonNull DownloadTask task) {
        final String path = task.getPath();
        if (path == null) return false;

        // Other one is running, cancel the current task.
        for (DownloadCall syncCall : runningSyncCalls) {
            if (syncCall.task == task) continue;

            final String otherPath = syncCall.task.getPath();
            if (otherPath != null && new File(path).equals(new File(otherPath))) {
                return true;
            }
        }

        for (DownloadCall asyncCall : runningAsyncCalls) {
            if (asyncCall.task == task) continue;

            final String otherPath = asyncCall.task.getPath();
            if (otherPath != null && new File(path).equals(new File(otherPath))) {
                return true;
            }
        }

        return false;
    }

    private boolean inspectForConflict(DownloadTask task) {
        return inspectForConflict(task, readyAsyncCalls)
                || inspectForConflict(task, runningAsyncCalls)
                || inspectForConflict(task, runningSyncCalls);

    }

    private boolean inspectForConflict(DownloadTask task, Collection<DownloadCall> calls) {
        final CallbackDispatcher callbackDispatcher = OkDownload.with().callbackDispatcher();
        for (DownloadCall call : calls) {
            if (call.task == task) {
                callbackDispatcher.dispatch()
                        .taskEnd(task, EndCause.SAME_TASK_BUSY, null);
                return true;
            }

            final String path = call.task.getPath();
            final String taskPah = task.getPath();
            if (path != null && taskPah != null && new File(path).equals(new File(taskPah))) {
                callbackDispatcher.dispatch().taskEnd(task, EndCause.FILE_BUSY, null);
                return true;
            }
        }

        return false;
    }

    private void processCalls() {
        if (runningAsyncCalls.size() >= maxTaskCount) return;
        if (readyAsyncCalls.isEmpty()) return;

        for (Iterator<DownloadCall> i = readyAsyncCalls.iterator(); i.hasNext(); ) {
            DownloadCall call = i.next();

            i.remove();

            final DownloadTask task = call.task;
            if (isFileConflictAfterRun(task)) {
                OkDownload.with().callbackDispatcher().dispatch().taskEnd(task, EndCause.FILE_BUSY,
                        null);
                continue;
            }

            runningAsyncCalls.add(call);
            executorService().execute(call);

            if (runningAsyncCalls.size() >= maxTaskCount) return;
        }
    }
}