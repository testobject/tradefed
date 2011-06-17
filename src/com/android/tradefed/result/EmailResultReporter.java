/*
 * Copyright (C) 2010 The Android Open Source Project
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
package com.android.tradefed.result;

import com.android.ddmlib.Log;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.Option.Importance;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.util.Email;
import com.android.tradefed.util.IEmail;
import com.android.tradefed.util.IEmail.Message;
import com.android.tradefed.util.StreamUtil;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

/**
 * A simple result reporter that sends emails for test results.
 */
@OptionClass(alias = "email")
public class EmailResultReporter extends CollectingTestListener implements ITestSummaryListener {
    private static final String LOG_TAG = "EmailResultReporter";
    private static final String DEFAULT_SUBJECT_TAG = "Tradefed";

    @Option(name="sender", description="The envelope-sender address to use for the messages.",
            importance = Importance.IF_UNSET)
    private String mSender = null;

    @Option(name="destination", description="One or more destination addresses.",
            importance = Importance.IF_UNSET)
    private Collection<String> mDestinations = new HashSet<String>();

    @Option(name = "subject-tag",
            description = "The tag to be added to the beginning of the email subject.")
    private String mSubjectTag = DEFAULT_SUBJECT_TAG;

    @Option(name = "send-only-on-failure",
            description = "Flag for sending email only on test failure.")
    private boolean mSendOnlyOnTestFailure = false;

    @Option(name = "send-only-on-inv-failure",
            description = "Flag for sending email only on invocation failure.")
    private boolean mSendOnlyOnInvFailure = false;

    private List<TestSummary> mSummaries = null;
    private Throwable mInvocationThrowable = null;
    private final IEmail mMailer;

    /**
     * Create a {@link EmailResultReporter}
     */
    public EmailResultReporter() {
        this(new Email());
    }

    /**
     * Create a {@link EmailResultReporter} with a custom {@link IEmail} instance to use.
     * <p/>
     * Exposed for unit testing.
     *
     * @param mailer the {@link IEmail} instance to use.
     */
    EmailResultReporter(IEmail mailer) {
        mMailer = mailer;
    }

    /**
     * Adds an email destination address.
     *
     * @param dest
     */
    void addDestination(String dest) {
        mDestinations.add(dest);
    }

    /**
     * Sets the send-only-on-inv-failure flag
     */
    void setSendOnlyOnInvocationFailure(boolean send) {
        mSendOnlyOnInvFailure = send;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void putSummary(List<TestSummary> summaries) {
        mSummaries = summaries;
    }

    /**
     * A method, meant to be overridden, which should do whatever filtering is decided and determine
     * whether a notification email should be sent for the test results.  Presumably, would consider
     * how many (if any) tests failed, prior failures of the same tests, etc.
     *
     * @return {@code true} if a notification email should be sent, {@code false} if not
     */
    protected boolean shouldSendMessage() {
        if (mSendOnlyOnTestFailure) {
            if (!hasFailedTests()) {
                Log.v(LOG_TAG, "Not sending email because there are no failures to report.");
                return false;
            }
        } else if (mSendOnlyOnInvFailure && getInvocationStatus().equals(
                InvocationStatus.SUCCESS)) {
            Log.v(LOG_TAG, "Not sending email because invocation succeeded.");
            return false;
        }
        return true;
    }

    /**
     * A method to generate the subject for email reports.  Will not be called if
     * {@link shouldSendMessage()} returns {@code false}.
     *
     * @return A {@link String} containing the subject to use for an email report
     */
    protected String generateEmailSubject() {
        return String.format("%s result for %s on build %d: %s", mSubjectTag,
                getBuildInfo().getTestTarget(), getBuildInfo().getBuildId(), getInvocationStatus());
    }

    /**
     * Returns the {@link InvocationStatus}
     */
    protected InvocationStatus getInvocationStatus() {
        if (mInvocationThrowable == null) {
            return InvocationStatus.SUCCESS;
        } else if (mInvocationThrowable instanceof BuildError) {
            return InvocationStatus.BUILD_ERROR;
        } else {
            return InvocationStatus.FAILED;
        }
    }

    /**
     * A method to generate the body for email reports.  Will not be called if
     * {@link shouldSendMessage()} returns {@code false}.
     *
     * @return A {@link String} containing the body to use for an email report
     */
    protected String generateEmailBody() {
        StringBuilder bodyBuilder = new StringBuilder();

        if (mInvocationThrowable != null) {
            bodyBuilder.append("Invocation failed: ");
            bodyBuilder.append(StreamUtil.getStackTrace(mInvocationThrowable));
            bodyBuilder.append("\n");
        }
        bodyBuilder.append(String.format("Test results:  %d passed, %d failed, %d error\n\n",
                getNumPassedTests(), getNumFailedTests(), getNumErrorTests()));
        for (TestRunResult result : getRunResults()) {
            if (!result.getRunMetrics().isEmpty()) {
                bodyBuilder.append(String.format("'%s' test run metrics: %s\n", result.getName(),
                        result.getRunMetrics()));
            }
        }
        bodyBuilder.append("\n");

        if (mSummaries != null) {
            for (TestSummary summary : mSummaries) {
                bodyBuilder.append("Invocation summary report: ");
                bodyBuilder.append(summary.getSummary().getString());
                if (!summary.getKvEntries().isEmpty()) {
                    bodyBuilder.append("\".\nSummary key-value dump:\n");
                    bodyBuilder.append(summary.getKvEntries().toString());
                }
            }
        }
        return bodyBuilder.toString();
    }

    @Override
    public void invocationFailed(Throwable t) {
        mInvocationThrowable = t;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void invocationEnded(long elapsedTime) {
        if (!shouldSendMessage()) {
            return;
        }

        if (mDestinations.isEmpty()) {
            Log.e(LOG_TAG, "Failed to send email because no destination addresses were set.");
            return;
        }

        Message msg = new Message();
        msg.setSender(mSender);
        msg.setSubject(generateEmailSubject());
        msg.setBody(generateEmailBody());
        Iterator<String> toAddress = mDestinations.iterator();
        while (toAddress.hasNext()) {
            msg.addTo(toAddress.next());
        }

        try {
            mMailer.send(msg);
        } catch (IllegalArgumentException e) {
            CLog.e("Failed to send email");
            CLog.e(e);
        } catch (IOException e) {
            CLog.e("Failed to send email");
            CLog.e(e);
        }
    }
}
