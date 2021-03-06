/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.samagra.customworkmanager.impl.utils;

import static com.samagra.customworkmanager.ExistingWorkPolicy.APPEND;
import static com.samagra.customworkmanager.ExistingWorkPolicy.KEEP;
import static com.samagra.customworkmanager.WorkInfo.State.BLOCKED;
import static com.samagra.customworkmanager.WorkInfo.State.CANCELLED;
import static com.samagra.customworkmanager.WorkInfo.State.ENQUEUED;
import static com.samagra.customworkmanager.WorkInfo.State.FAILED;
import static com.samagra.customworkmanager.WorkInfo.State.RUNNING;
import static com.samagra.customworkmanager.WorkInfo.State.SUCCEEDED;
import static com.samagra.customworkmanager.impl.workers.ConstraintTrackingWorker.ARGUMENT_CLASS_NAME;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;
import com.samagra.customworkmanager.Constraints;
import com.samagra.customworkmanager.Data;
import com.samagra.customworkmanager.ExistingWorkPolicy;
import com.samagra.customworkmanager.Logger;
import com.samagra.customworkmanager.Operation;
import com.samagra.customworkmanager.WorkInfo;
import com.samagra.customworkmanager.WorkRequest;
import com.samagra.customworkmanager.impl.OperationImpl;
import com.samagra.customworkmanager.impl.Scheduler;
import com.samagra.customworkmanager.impl.Schedulers;
import com.samagra.customworkmanager.impl.WorkContinuationImpl;
import com.samagra.customworkmanager.impl.WorkDatabase;
import com.samagra.customworkmanager.impl.WorkManagerImpl;
import com.samagra.customworkmanager.impl.background.systemalarm.RescheduleReceiver;
import com.samagra.customworkmanager.impl.model.Dependency;
import com.samagra.customworkmanager.impl.model.DependencyDao;
import com.samagra.customworkmanager.impl.model.WorkName;
import com.samagra.customworkmanager.impl.model.WorkSpec;
import com.samagra.customworkmanager.impl.model.WorkSpecDao;
import com.samagra.customworkmanager.impl.model.WorkTag;
import com.samagra.customworkmanager.impl.workers.ConstraintTrackingWorker;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Manages the enqueuing of a {@link WorkContinuationImpl}.
 *
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EnqueueRunnable implements Runnable {

    private static final String TAG = Logger.tagWithPrefix("EnqueueRunnable");

    private final WorkContinuationImpl mWorkContinuation;
    private final OperationImpl mOperation;

    public EnqueueRunnable(@NonNull WorkContinuationImpl workContinuation) {
        mWorkContinuation = workContinuation;
        mOperation = new OperationImpl();
    }

    @Override
    public void run() {
        try {
            if (mWorkContinuation.hasCycles()) {
                throw new IllegalStateException(
                        String.format("WorkContinuation has cycles (%s)", mWorkContinuation));
            }
            boolean needsScheduling = addToDatabase();
            if (needsScheduling) {
                // Enable RescheduleReceiver, only when there are Worker's that need scheduling.
                final Context context =
                        mWorkContinuation.getWorkManagerImpl().getApplicationContext();
                PackageManagerHelper.setComponentEnabled(context, RescheduleReceiver.class, true);
                scheduleWorkInBackground();
            }
            mOperation.setState(Operation.SUCCESS);
        } catch (Throwable exception) {
            mOperation.setState(new Operation.State.FAILURE(exception));
        }
    }

    /**
     * @return The {@link Operation} that encapsulates the state of the {@link EnqueueRunnable}.
     */
    public Operation getOperation() {
        return mOperation;
    }

    /**
     * Adds the {@link WorkSpec}'s to the datastore, parent first.
     * Schedules work on the background scheduler, if transaction is successful.
     */
    @VisibleForTesting
    public boolean addToDatabase() {
        WorkManagerImpl workManagerImpl = mWorkContinuation.getWorkManagerImpl();
        WorkDatabase workDatabase = workManagerImpl.getWorkDatabase();
        workDatabase.beginTransaction();
        try {
            boolean needsScheduling = processContinuation(mWorkContinuation);
            workDatabase.setTransactionSuccessful();
            return needsScheduling;
        } finally {
            workDatabase.endTransaction();
        }
    }

    /**
     * Schedules work on the background scheduler.
     */
    @VisibleForTesting
    public void scheduleWorkInBackground() {
        WorkManagerImpl workManager = mWorkContinuation.getWorkManagerImpl();
        Schedulers.schedule(
                workManager.getConfiguration(),
                workManager.getWorkDatabase(),
                workManager.getSchedulers());
    }

    private static boolean processContinuation(@NonNull WorkContinuationImpl workContinuation) {
        boolean needsScheduling = false;
        List<WorkContinuationImpl> parents = workContinuation.getParents();
        if (parents != null) {
            for (WorkContinuationImpl parent : parents) {
                // When chaining off a completed continuation we need to pay
                // attention to parents that may have been marked as enqueued before.
                if (!parent.isEnqueued()) {
                    needsScheduling |= processContinuation(parent);
                } else {
                    Logger.get().warning(TAG, String.format("Already enqueued work ids (%s).",
                            TextUtils.join(", ", parent.getIds())));
                }
            }
        }
        needsScheduling |= enqueueContinuation(workContinuation);
        return needsScheduling;
    }

    private static boolean enqueueContinuation(@NonNull WorkContinuationImpl workContinuation) {
        Set<String> prerequisiteIds = WorkContinuationImpl.prerequisitesFor(workContinuation);

        boolean needsScheduling = enqueueWorkWithPrerequisites(
                workContinuation.getWorkManagerImpl(),
                workContinuation.getWork(),
                prerequisiteIds.toArray(new String[0]),
                workContinuation.getName(),
                workContinuation.getExistingWorkPolicy());

        workContinuation.markEnqueued();
        return needsScheduling;
    }

    /**
     * Enqueues the {@link WorkSpec}'s while keeping track of the prerequisites.
     *
     * @return {@code true} If there is any scheduling to be done.
     */
    private static boolean enqueueWorkWithPrerequisites(
            WorkManagerImpl workManagerImpl,
            @NonNull List<? extends WorkRequest> workList,
            String[] prerequisiteIds,
            String name,
            ExistingWorkPolicy existingWorkPolicy) {

        boolean needsScheduling = false;

        long currentTimeMillis = System.currentTimeMillis();
        WorkDatabase workDatabase = workManagerImpl.getWorkDatabase();

        boolean hasPrerequisite = (prerequisiteIds != null && prerequisiteIds.length > 0);
        boolean hasCompletedAllPrerequisites = true;
        boolean hasFailedPrerequisites = false;
        boolean hasCancelledPrerequisites = false;

        if (hasPrerequisite) {
            // If there are prerequisites, make sure they actually exist before enqueuing
            // anything.  Prerequisites may not exist if we are using unique tags, because the
            // chain of work could have been wiped out already.
            for (String id : prerequisiteIds) {
                WorkSpec prerequisiteWorkSpec = workDatabase.workSpecDao().getWorkSpec(id);
                if (prerequisiteWorkSpec == null) {
                    Logger.get().error(TAG,
                            String.format("Prerequisite %s doesn't exist; not enqueuing", id));
                    return false;
                }

                WorkInfo.State prerequisiteState = prerequisiteWorkSpec.state;
                hasCompletedAllPrerequisites &= (prerequisiteState == SUCCEEDED);
                if (prerequisiteState == FAILED) {
                    hasFailedPrerequisites = true;
                } else if (prerequisiteState == CANCELLED) {
                    hasCancelledPrerequisites = true;
                }
            }
        }

        boolean isNamed = !TextUtils.isEmpty(name);

        // We only apply existing work policies for unique tag sequences that are the beginning of
        // chains.
        boolean shouldApplyExistingWorkPolicy = isNamed && !hasPrerequisite;
        if (shouldApplyExistingWorkPolicy) {
            // Get everything with the unique tag.
            List<WorkSpec.IdAndState> existingWorkSpecIdAndStates =
                    workDatabase.workSpecDao().getWorkSpecIdAndStatesForName(name);

            if (!existingWorkSpecIdAndStates.isEmpty()) {
                // If appending, these are the new prerequisites.
                if (existingWorkPolicy == APPEND) {
                    DependencyDao dependencyDao = workDatabase.dependencyDao();
                    List<String> newPrerequisiteIds = new ArrayList<>();
                    for (WorkSpec.IdAndState idAndState : existingWorkSpecIdAndStates) {
                        if (!dependencyDao.hasDependents(idAndState.id)) {
                            hasCompletedAllPrerequisites &= (idAndState.state == SUCCEEDED);
                            if (idAndState.state == FAILED) {
                                hasFailedPrerequisites = true;
                            } else if (idAndState.state == CANCELLED) {
                                hasCancelledPrerequisites = true;
                            }
                            newPrerequisiteIds.add(idAndState.id);
                        }
                    }
                    prerequisiteIds = newPrerequisiteIds.toArray(prerequisiteIds);
                    hasPrerequisite = (prerequisiteIds.length > 0);
                } else {
                    // If we're keeping existing work, make sure to do so only if something is
                    // enqueued or running.
                    if (existingWorkPolicy == KEEP) {
                        for (WorkSpec.IdAndState idAndState : existingWorkSpecIdAndStates) {
                            if (idAndState.state == ENQUEUED || idAndState.state == RUNNING) {
                                return false;
                            }
                        }
                    }

                    // Cancel all of these workers.
                    // Don't allow rescheduling in CancelWorkRunnable because it will happen inside
                    // the current transaction.  We want it to happen separately to avoid race
                    // conditions (see ag/4502245, which tries to avoid work trying to run before
                    // it's actually been committed to the database).
                    CancelWorkRunnable.forName(name, workManagerImpl, false).run();
                    // Because we cancelled some work but didn't allow rescheduling inside
                    // CancelWorkRunnable, we need to make sure we do schedule work at the end of
                    // this runnable.
                    needsScheduling = true;

                    // And delete all the database records.
                    WorkSpecDao workSpecDao = workDatabase.workSpecDao();
                    for (WorkSpec.IdAndState idAndState : existingWorkSpecIdAndStates) {
                        workSpecDao.delete(idAndState.id);
                    }
                }
            }
        }

        for (WorkRequest work : workList) {
            WorkSpec workSpec = work.getWorkSpec();

            if (hasPrerequisite && !hasCompletedAllPrerequisites) {
                if (hasFailedPrerequisites) {
                    workSpec.state = FAILED;
                } else if (hasCancelledPrerequisites) {
                    workSpec.state = CANCELLED;
                } else {
                    workSpec.state = BLOCKED;
                }
            } else {
                // Set scheduled times only for work without prerequisites and that are
                // not periodic. Dependent work will set their scheduled times when they are
                // unblocked.
                if (!workSpec.isPeriodic()) {
                    workSpec.periodStartTime = currentTimeMillis;
                } else {
                    workSpec.periodStartTime = 0L;
                }
            }

            if (Build.VERSION.SDK_INT >= WorkManagerImpl.MIN_JOB_SCHEDULER_API_LEVEL
                    && Build.VERSION.SDK_INT <= 25) {
                tryDelegateConstrainedWorkSpec(workSpec);
            } else if (Build.VERSION.SDK_INT <= WorkManagerImpl.MAX_PRE_JOB_SCHEDULER_API_LEVEL
                    && usesScheduler(workManagerImpl, Schedulers.GCM_SCHEDULER)) {
                tryDelegateConstrainedWorkSpec(workSpec);
            }

            // If we have one WorkSpec with an enqueued state, then we need to schedule.
            if (workSpec.state == ENQUEUED) {
                needsScheduling = true;
            }

            workDatabase.workSpecDao().insertWorkSpec(workSpec);

            if (hasPrerequisite) {
                for (String prerequisiteId : prerequisiteIds) {
                    Dependency dep = new Dependency(work.getStringId(), prerequisiteId);
                    workDatabase.dependencyDao().insertDependency(dep);
                }
            }

            for (String tag : work.getTags()) {
                workDatabase.workTagDao().insert(new WorkTag(tag, work.getStringId()));
            }

            if (isNamed) {
                workDatabase.workNameDao().insert(new WorkName(name, work.getStringId()));
            }
        }
        return needsScheduling;
    }

    private static void tryDelegateConstrainedWorkSpec(WorkSpec workSpec) {
        // requiresBatteryNotLow and requiresStorageNotLow require API 26 for JobScheduler.
        // Delegate to ConstraintTrackingWorker between API 23-25.
        Constraints constraints = workSpec.constraints;
        if (constraints.requiresBatteryNotLow() || constraints.requiresStorageNotLow()) {
            String workerClassName = workSpec.workerClassName;
            Data.Builder builder = new Data.Builder();
            // Copy all arguments
            builder.putAll(workSpec.input)
                    .putString(ARGUMENT_CLASS_NAME, workerClassName);
            workSpec.workerClassName = ConstraintTrackingWorker.class.getName();
            workSpec.input = builder.build();
        }
    }

    /**
     * @param className The fully qualified class name of the {@link Scheduler}
     * @return {@code true} if the {@link Scheduler} class is being used by WorkManager.
     */
    private static boolean usesScheduler(
            @NonNull WorkManagerImpl workManager,
            @NonNull String className) {

        try {
            Class<?> klass = Class.forName(className);
            for (Scheduler scheduler : workManager.getSchedulers()) {
                if (klass.isAssignableFrom(scheduler.getClass())) {
                    return true;
                }
            }
            return false;
        } catch (ClassNotFoundException ignore) {
            return false;
        }
    }
}
