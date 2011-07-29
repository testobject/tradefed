/*
 * Copyright (C) 2011 The Android Open Source Project
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
package com.android.wireless.tests;

import com.android.ddmlib.testrunner.IRemoteAndroidTestRunner;
import com.android.ddmlib.testrunner.RemoteAndroidTestRunner;
import com.android.tradefed.config.Option;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.result.BugreportCollector;
import com.android.tradefed.result.CollectingTestListener;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.testtype.IDeviceTest;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.log.LogUtil.CLog;

import java.util.HashMap;
import java.util.Map;

import junit.framework.Assert;

/**
 * Run radio startup stress test. The test stresses the radio by run-time reboot a device
 * for multiple times. In each iteration, voice and/or data connection is verified.
 */
public class RadioStressTest implements IRemoteTest, IDeviceTest {
    private ITestDevice mTestDevice = null;
    private static String mTestName = "RadioStartupStress";

    // Define metrics for result report
    private static final String mMetricsName = "RadioStartupStress";

    @Option(name="iteration",
            description="The number of times to run the tests")
    private int mIteration = 100;

    @Option(name="call-duration",
            description="The time of a call to be held in the test (in seconds)")
    private String mCallDuration = "5";

    @Option(name="phone-number",
            description="The phone number used for outgoing call test")
    private String mPhoneNumber = null;

    @Option(name="none-phone-device-list",
            description="A list of product type that is not voice capable")
    private String mNonePhoneDevices = null;

    /**
     * Run radio startup stress test, capture bugreport if the test failed.
     * Report results to dashboard after the test
     */
    @Override
    public void run(ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        CLog.d("input options: mIteration(%s), mCallDuration(%s), mPhoneNumber(%s), "
                + "mNonePhoneDevices(%s)", mIteration, mCallDuration, mPhoneNumber,
                mNonePhoneDevices);
        Assert.assertNotNull(mTestDevice);
        Assert.assertNotNull(mPhoneNumber);
        Assert.assertTrue(RadioHelper.radioActivation(mTestDevice));

        boolean voiceTest = RadioHelper.isVoiceCapable(mTestDevice, mNonePhoneDevices);
        int mSuccessRun = 0;
        for (int i = 0; i < mIteration; i++) {
            // reset device before rebooting
            CLog.d("Radio startup test iteration : %d", i);
            RadioHelper.resetBootComplete(mTestDevice);

            // run-time reboot device
            mTestDevice.executeShellCommand("stop");
            mTestDevice.executeShellCommand("start");

            Assert.assertTrue("Device failed to reboot",
                    RadioHelper.waitForBootComplete(mTestDevice));
            mTestDevice.waitForDeviceAvailable();

            // Setup up device
            mTestDevice.enableAdbRoot();
            mTestDevice.postBootSetup();
            mTestDevice.clearErrorDialogs();

            // verify voice connection
            if (voiceTest) {
                boolean voiceRes = verifyVoiceConnection(listener);
                try {
                    Thread.sleep(3 * 60 * 1000); // time for setup data call
                } catch (Exception e) {
                    CLog.e("thread is interrupted: %s", e.toString());
                }
                boolean dataRes = verifyDataConnection();
                if (voiceRes && dataRes) {
                    mSuccessRun++;
                }
            } else {
                if (verifyDataConnection()) {
                    mSuccessRun++;
                }
            }
        }

        CLog.d("success runs out of total %d runs: %d", mIteration, mSuccessRun);
        Assert.assertEquals(mIteration, mSuccessRun);

        Map<String, String> runMetrics = new HashMap<String, String>(1);
        runMetrics.put("iteration", String.valueOf(mSuccessRun));
        reportMetrics(mMetricsName, runMetrics, listener);
    }

    private boolean verifyVoiceConnection(ITestInvocationListener listener)
        throws DeviceNotAvailableException {
        CLog.d("Verify voice connection");
        IRemoteAndroidTestRunner runner = new RemoteAndroidTestRunner(
                TelephonyTest.TEST_PACKAGE_NAME,
                TelephonyTest.TEST_RUNNER_NAME, mTestDevice.getIDevice());
        runner.setClassName(TelephonyTest.TEST_CLASS_NAME);
        runner.setMethodName(TelephonyTest.TEST_CLASS_NAME, TelephonyTest.TEST_METHOD);
        runner.addInstrumentationArg("callduration", mCallDuration);
        runner.addInstrumentationArg("phonenumber", mPhoneNumber);
        runner.addInstrumentationArg("repeatcount", "1");

        // Add bugreport listener for failed test
        BugreportCollector bugListener = new
            BugreportCollector(listener, mTestDevice);
        bugListener.addPredicate(BugreportCollector.AFTER_FAILED_TESTCASES);
        bugListener.setDescriptiveName(mTestName);
        CollectingTestListener collectListener = new CollectingTestListener();

        mTestDevice.runInstrumentationTests(runner, bugListener, collectListener);
        if (collectListener.hasFailedTests()) {
            CLog.d("Voice call failed.");
            return false;
        }
        return true;
    }

    private boolean verifyDataConnection() throws DeviceNotAvailableException {
        return RadioHelper.pingTest(mTestDevice);
    }

    /**
     * Report run metrics by creating an empty test run to stick them in
     * <p />
     * Exposed for unit testing
     */
    private void reportMetrics(String metricsName, Map<String, String> metrics,
            ITestInvocationListener listener) {
        // Create an empty testRun to report the parsed runMetrics
        CLog.d("About to report metrics to %s: %s", metricsName, metrics);
        listener.testRunStarted(metricsName, 0);
        listener.testRunEnded(0, metrics);
    }

    @Override
    public void setDevice(ITestDevice testDevice) {
        mTestDevice = testDevice;
    }

    @Override
    public ITestDevice getDevice() {
        return mTestDevice;
    }
}