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

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import com.samagra.customworkmanager.Configuration;
import com.samagra.customworkmanager.impl.Scheduler;
import com.samagra.customworkmanager.impl.WorkDatabase;
import com.samagra.customworkmanager.impl.WorkManagerImpl;
import com.samagra.customworkmanager.impl.model.WorkSpec;
import com.samagra.customworkmanager.impl.model.WorkSpecDao;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class ForceStopRunnableTest {

    private Context mContext;
    private WorkManagerImpl mWorkManager;
    private Scheduler mScheduler;
    private Configuration mConfiguration;
    private WorkDatabase mWorkDatabase;
    private WorkSpecDao mWorkSpecDao;
    private Preferences mPreferences;
    private ForceStopRunnable mRunnable;

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext().getApplicationContext();
        mWorkManager = mock(WorkManagerImpl.class);
        mWorkDatabase = mock(WorkDatabase.class);
        mWorkSpecDao = mock(WorkSpecDao.class);
        mPreferences = mock(Preferences.class);
        mScheduler = mock(Scheduler.class);
        mConfiguration = new Configuration.Builder().build();

        when(mWorkManager.getWorkDatabase()).thenReturn(mWorkDatabase);
        when(mWorkManager.getSchedulers()).thenReturn(Collections.singletonList(mScheduler));
        when(mWorkDatabase.workSpecDao()).thenReturn(mWorkSpecDao);
        when(mWorkManager.getPreferences()).thenReturn(mPreferences);
        when(mWorkManager.getConfiguration()).thenReturn(mConfiguration);
        mRunnable = new ForceStopRunnable(mContext, mWorkManager);
    }

    @Test
    public void testIntent() {
        Intent intent = ForceStopRunnable.getIntent(mContext);
        ComponentName componentName = intent.getComponent();
        assertThat(componentName.getClassName(),
                is(ForceStopRunnable.BroadcastReceiver.class.getName()));
        assertThat(intent.getAction(), is(ForceStopRunnable.ACTION_FORCE_STOP_RESCHEDULE));
    }

    @Test
    public void testReschedulesOnForceStop() {
        ForceStopRunnable runnable = spy(mRunnable);
        when(runnable.shouldRescheduleWorkers()).thenReturn(false);
        when(runnable.isForceStopped()).thenReturn(true);
        runnable.run();
        verify(mWorkManager, times(1)).rescheduleEligibleWork();
        verify(mWorkManager, times(1)).onForceStopRunnableCompleted();
    }

    @Test
    public void test_doNothingWhenNotForceStopped() {
        ForceStopRunnable runnable = spy(mRunnable);
        when(runnable.shouldRescheduleWorkers()).thenReturn(false);
        when(runnable.isForceStopped()).thenReturn(false);
        runnable.run();
        verify(mWorkManager, times(0)).rescheduleEligibleWork();
        verify(mWorkManager, times(1)).onForceStopRunnableCompleted();
    }

    @Test
    public void test_rescheduleWorkers_updatesSharedPreferences() {
        ForceStopRunnable runnable = spy(mRunnable);
        when(runnable.shouldRescheduleWorkers()).thenReturn(true);
        runnable.run();
        verify(mPreferences, times(1)).setNeedsReschedule(false);
    }

    @Test
    public void test_UnfinishedWork_getsScheduled() {
        ForceStopRunnable runnable = spy(mRunnable);
        when(runnable.shouldRescheduleWorkers()).thenReturn(false);
        when(runnable.isForceStopped()).thenReturn(false);
        String id = "id";
        String worker = "Worker";
        WorkSpec workSpec = new WorkSpec(id, worker);

        when(mWorkSpecDao.getRunningWork()).thenReturn(Collections.singletonList(workSpec));
        when(mWorkSpecDao.getEligibleWorkForScheduling(anyInt())).thenReturn(
                Collections.singletonList(workSpec));

        runnable.run();
        verify(mWorkSpecDao, times(2))
                .markWorkSpecScheduled(eq(id), anyLong());

        verify(mScheduler, times(1)).schedule(eq(workSpec));
    }
}
