/*
 * Copyright (C) 2006 The Android Open Source Project
 * Copyright (c) 2011-2012, Code Aurora Forum. All rights reserved.
 *
 * Not a Contribution, Apache license notifications and license are retained
 * for attribution purposes only
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

package com.android.phone;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.NeighboringCellInfo;
import android.telephony.CellInfo;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.DefaultPhoneNotifier;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.IOemHookCallback;
import com.android.internal.telephony.QosSpec;

import java.util.List;
import java.util.ArrayList;

/**
 * Implementation of the ITelephony interface.
 */
public class PhoneInterfaceManager extends ITelephony.Stub {
    private static final String LOG_TAG = "PhoneInterfaceManager";
    private static final boolean DBG = (PhoneApp.DBG_LEVEL >= 2);

    // Message codes used with mMainThreadHandler
    private static final int CMD_HANDLE_PIN_MMI = 1;
    private static final int CMD_HANDLE_NEIGHBORING_CELL = 2;
    private static final int EVENT_NEIGHBORING_CELL_DONE = 3;
    private static final int CMD_ANSWER_RINGING_CALL = 4;
    private static final int CMD_END_CALL = 5;  // not used yet
    private static final int CMD_SILENCE_RINGER = 6;
    private static final int CMD_INVOKE_OEM_RIL_REQUEST = 7;
    private static final int EVENT_INVOKE_OEM_RIL_REQUEST = 8;
    private static final int EVENT_UNSOL_OEM_HOOK_EXT_APP = 9;
    private static final int CMD_INVOKE_OEM_RIL_REQUEST_ASYNC = 12;
    private static final int EVENT_INVOKE_OEM_RIL_REQUEST_ASYNC_DONE = 13;

    /** The singleton instance. */
    private static PhoneInterfaceManager sInstance;

    PhoneApp mApp;
    Phone mPhone;
    CallManager mCM;
    MainThreadHandler mMainThreadHandler;

    /**
     * A request object for use with {@link MainThreadHandler}. Requesters should wait() on the
     * request after sending. The main thread will notify the request when it is complete.
     */
    private static final class MainThreadRequest {
        /** The argument to use for the request */
        public Object argument;
        /** The result of the request that is run on the main thread */
        public Object result;

        public MainThreadRequest(Object argument) {
            this.argument = argument;
        }
    }

    /**
     * A request object for use with {@link MainThreadHandler}. The main thread
     * will notify the request when it is complete.
     */
    private static final class MainThreadRequestAsync {
        /** The first argument to use for the request */
        public Object arg1;
        /** The second argument to use for the callback */
        public Object arg2;
        /** The result of the request that is run on the main thread */
        public Object result;

        public MainThreadRequestAsync(Object arg1, Object arg2) {
            this.arg1 = arg1;
            this.arg2 = arg2;
        }
    }

    /**
     * A handler that processes messages on the main thread in the phone process. Since many
     * of the Phone calls are not thread safe this is needed to shuttle the requests from the
     * inbound binder threads to the main thread in the phone process.  The Binder thread
     * may provide a {@link MainThreadRequest} object in the msg.obj field that they are waiting
     * on, which will be notified when the operation completes and will contain the result of the
     * request.
     *
     * <p>If a MainThreadRequest object is provided in the msg.obj field,
     * note that request.result must be set to something non-null for the calling thread to
     * unblock.
     */
    protected class MainThreadHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            MainThreadRequest request;
            MainThreadRequestAsync requestAsync;
            Message onCompleted;
            AsyncResult ar;

            switch (msg.what) {
                case CMD_HANDLE_PIN_MMI:
                    request = (MainThreadRequest) msg.obj;
                    request.result = Boolean.valueOf(
                            mPhone.handlePinMmi((String) request.argument));
                    // Wake up the requesting thread
                    synchronized (request) {
                        request.notifyAll();
                    }
                    break;

                case CMD_HANDLE_NEIGHBORING_CELL:
                    request = (MainThreadRequest) msg.obj;
                    onCompleted = obtainMessage(EVENT_NEIGHBORING_CELL_DONE,
                            request);
                    mPhone.getNeighboringCids(onCompleted);
                    break;

                case EVENT_NEIGHBORING_CELL_DONE:
                    ar = (AsyncResult) msg.obj;
                    request = (MainThreadRequest) ar.userObj;
                    if (ar.exception == null && ar.result != null) {
                        request.result = ar.result;
                    } else {
                        // create an empty list to notify the waiting thread
                        request.result = new ArrayList<NeighboringCellInfo>();
                    }
                    // Wake up the requesting thread
                    synchronized (request) {
                        request.notifyAll();
                    }
                    break;

                case CMD_ANSWER_RINGING_CALL:
                    answerRingingCallInternal();
                    break;

                case CMD_SILENCE_RINGER:
                    silenceRingerInternal();
                    break;

                case CMD_END_CALL:
                    request = (MainThreadRequest) msg.obj;
                    boolean hungUp = false;
                    int phoneType = mPhone.getPhoneType();
                    if (phoneType == Phone.PHONE_TYPE_CDMA) {
                        // CDMA: If the user presses the Power button we treat it as
                        // ending the complete call session
                        hungUp = PhoneUtils.hangupRingingAndActive(mPhone);
                    } else if (phoneType == Phone.PHONE_TYPE_GSM) {
                        // GSM: End the call as per the Phone state
                        hungUp = PhoneUtils.hangup(mCM);
                    } else {
                        throw new IllegalStateException("Unexpected phone type: " + phoneType);
                    }
                    if (DBG) log("CMD_END_CALL: " + (hungUp ? "hung up!" : "no call to hang up"));
                    request.result = hungUp;
                    // Wake up the requesting thread
                    synchronized (request) {
                        request.notifyAll();
                    }
                    break;

                case CMD_INVOKE_OEM_RIL_REQUEST:
                    request = (MainThreadRequest)msg.obj;
                    onCompleted = obtainMessage(EVENT_INVOKE_OEM_RIL_REQUEST, request);
                    mPhone.invokeOemRilRequestRaw((byte[])request.argument, onCompleted);
                    break;

                case EVENT_INVOKE_OEM_RIL_REQUEST:
                    ar = (AsyncResult)msg.obj;
                    request = (MainThreadRequest)ar.userObj;
                    request.result = ar;
                    // Wake up the requesting thread
                    synchronized (request) {
                        request.notifyAll();
                    }
                    break;

                case CMD_INVOKE_OEM_RIL_REQUEST_ASYNC:
                    requestAsync = (MainThreadRequestAsync) msg.obj;
                    onCompleted = obtainMessage(
                            EVENT_INVOKE_OEM_RIL_REQUEST_ASYNC_DONE, requestAsync);
                    mPhone.invokeOemRilRequestRaw((byte[]) requestAsync.arg1,
                            onCompleted);
                    break;

                case EVENT_INVOKE_OEM_RIL_REQUEST_ASYNC_DONE:
                    ar = (AsyncResult) msg.obj;
                    requestAsync = (MainThreadRequestAsync) ar.userObj;
                    requestAsync.result = ar.result;
                    IOemHookCallback cb = (IOemHookCallback) requestAsync.arg2;
                    try {
                        cb.onOemHookResponse((byte[]) (requestAsync.result));
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                    break;

                case EVENT_UNSOL_OEM_HOOK_EXT_APP:
                    ar = (AsyncResult)msg.obj;
                    broadcastUnsolOemHookIntent((byte[])(ar.result));
                    break;

                default:
                    Log.w(LOG_TAG, "MainThreadHandler: unexpected message code: " + msg.what);
                    break;
            }
        }
    }

    /**
     * Posts the specified command to be executed on the main thread,
     * waits for the request to complete, and returns the result.
     * @see sendRequestAsync
     */
    private Object sendRequest(int command, Object argument) {
        if (Looper.myLooper() == mMainThreadHandler.getLooper()) {
            throw new RuntimeException("This method will deadlock if called from the main thread.");
        }

        MainThreadRequest request = new MainThreadRequest(argument);
        Message msg = mMainThreadHandler.obtainMessage(command, request);
        msg.sendToTarget();

        // Wait for the request to complete
        synchronized (request) {
            while (request.result == null) {
                try {
                    request.wait();
                } catch (InterruptedException e) {
                    // Do nothing, go back and wait until the request is complete
                }
            }
        }
        return request.result;
    }

    public void broadcastUnsolOemHookIntent(byte[] payload) {
        Intent intent = new Intent(TelephonyIntents.ACTION_UNSOL_RESPONSE_OEM_HOOK_RAW);
        intent.putExtra("payload", payload);
        Log.d(LOG_TAG,"Broadcasting intent ACTION_UNSOL_RESPONSE_OEM_HOOK_RAW");
        mApp.mContext.sendBroadcast(intent);
    }

    /**
     * Asynchronous ("fire and forget") version of sendRequest():
     * Posts the specified command to be executed on the main thread, and
     * returns immediately.
     * @see sendRequest
     */
    private void sendRequestAsync(int command) {
        mMainThreadHandler.sendEmptyMessage(command);
    }

    /**
     * Posts the specified command to be executed on the main thread, and
     * returns the result without waiting for the request to complete,
     *
     * @see sendRequestAsync
     */
    private void sendRequestAsync(int command, Object arg1, Object arg2) {
        MainThreadRequestAsync request = new MainThreadRequestAsync(arg1, arg2);
        Message msg = mMainThreadHandler.obtainMessage(command, request);
        msg.sendToTarget();
    }

    /**
     * Initialize the singleton PhoneInterfaceManager instance.
     * This is only done once, at startup, from PhoneApp.onCreate().
     */
    /* package */ static PhoneInterfaceManager init(PhoneApp app, Phone phone) {
        synchronized (PhoneInterfaceManager.class) {
            if (sInstance == null) {
                sInstance = new PhoneInterfaceManager(app, phone);
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    /** Private constructor; @see init() */
    private PhoneInterfaceManager(PhoneApp app, Phone phone) {
        mApp = app;
        mPhone = phone;
        mCM = PhoneApp.getInstance().mCM;
        mMainThreadHandler = new MainThreadHandler();
        Log.d(LOG_TAG, " Registering for UNSOL OEM HOOK Responses to deliver external apps");
        mPhone.setOnUnsolOemHookExtApp(mMainThreadHandler, EVENT_UNSOL_OEM_HOOK_EXT_APP, null);
        publish();
    }

    private void publish() {
        if (DBG) log("publish: " + this);

        ServiceManager.addService("phone", this);
    }

    //
    // Implementation of the ITelephony interface.
    //

    public void dial(String number) {
        if (DBG) log("dial: " + number);
        // No permission check needed here: This is just a wrapper around the
        // ACTION_DIAL intent, which is available to any app since it puts up
        // the UI before it does anything.

        String url = createTelUrl(number);
        if (url == null) {
            return;
        }

        // PENDING: should we just silently fail if phone is offhook or ringing?
        Phone.State state = mCM.getState();
        if (state != Phone.State.OFFHOOK && state != Phone.State.RINGING) {
            Intent  intent = new Intent(Intent.ACTION_DIAL, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mApp.mContext.startActivity(intent);
        }
    }

    public void call(String number) {
        if (DBG) log("call: " + number);

        // This is just a wrapper around the ACTION_CALL intent, but we still
        // need to do a permission check since we're calling startActivity()
        // from the context of the phone app.
        enforceCallPermission();

        String url = createTelUrl(number);
        if (url == null) {
            return;
        }

        Intent intent = new Intent(Intent.ACTION_CALL, Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mApp.mContext.startActivity(intent);
    }

    private boolean showCallScreenInternal(boolean specifyInitialDialpadState,
                                           boolean initialDialpadState) {
        if (!PhoneApp.sVoiceCapable) {
            // Never allow the InCallScreen to appear on data-only devices.
            return false;
        }
        if (isIdle()) {
            return false;
        }
        // If the phone isn't idle then go to the in-call screen
        long callingId = Binder.clearCallingIdentity();
        try {
            Intent intent;
            if (specifyInitialDialpadState) {
                intent = PhoneApp.createInCallIntent(initialDialpadState);
            } else {
                intent = PhoneApp.createInCallIntent();
            }
            try {
                mApp.mContext.startActivity(intent);
            } catch (ActivityNotFoundException e) {
                // It's possible that the in-call UI might not exist
                // (like on non-voice-capable devices), although we
                // shouldn't be trying to bring up the InCallScreen on
                // devices like that in the first place!
                Log.w(LOG_TAG, "showCallScreenInternal: "
                      + "transition to InCallScreen failed; intent = " + intent);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
        return true;
    }

    // Show the in-call screen without specifying the initial dialpad state.
    public boolean showCallScreen() {
        return showCallScreenInternal(false, false);
    }

    // The variation of showCallScreen() that specifies the initial dialpad state.
    // (Ideally this would be called showCallScreen() too, just with a different
    // signature, but AIDL doesn't allow that.)
    public boolean showCallScreenWithDialpad(boolean showDialpad) {
        return showCallScreenInternal(true, showDialpad);
    }

    /**
     * End a call based on call state
     * @return true is a call was ended
     */
    public boolean endCall() {
        enforceCallPermission();
        return (Boolean) sendRequest(CMD_END_CALL, null);
    }

    public void answerRingingCall() {
        if (DBG) log("answerRingingCall...");
        // TODO: there should eventually be a separate "ANSWER_PHONE" permission,
        // but that can probably wait till the big TelephonyManager API overhaul.
        // For now, protect this call with the MODIFY_PHONE_STATE permission.
        enforceModifyPermission();
        sendRequestAsync(CMD_ANSWER_RINGING_CALL);
    }

    /**
     * Make the actual telephony calls to implement answerRingingCall().
     * This should only be called from the main thread of the Phone app.
     * @see answerRingingCall
     *
     * TODO: it would be nice to return true if we answered the call, or
     * false if there wasn't actually a ringing incoming call, or some
     * other error occurred.  (In other words, pass back the return value
     * from PhoneUtils.answerCall() or PhoneUtils.answerAndEndActive().)
     * But that would require calling this method via sendRequest() rather
     * than sendRequestAsync(), and right now we don't actually *need* that
     * return value, so let's just return void for now.
     */
    private void answerRingingCallInternal() {
        final boolean hasRingingCall = !mPhone.getRingingCall().isIdle();
        if (hasRingingCall) {
            final boolean hasActiveCall = !mPhone.getForegroundCall().isIdle();
            final boolean hasHoldingCall = !mPhone.getBackgroundCall().isIdle();
            if (hasActiveCall && hasHoldingCall) {
                // Both lines are in use!
                // TODO: provide a flag to let the caller specify what
                // policy to use if both lines are in use.  (The current
                // behavior is hardwired to "answer incoming, end ongoing",
                // which is how the CALL button is specced to behave.)
                PhoneUtils.answerAndEndActive(mCM, mCM.getFirstActiveRingingCall());
                return;
            } else {
                // answerCall() will automatically hold the current active
                // call, if there is one.
                PhoneUtils.answerCall(mCM.getFirstActiveRingingCall());
                return;
            }
        } else {
            // No call was ringing.
            return;
        }
    }

    public void silenceRinger() {
        if (DBG) log("silenceRinger...");
        // TODO: find a more appropriate permission to check here.
        // (That can probably wait till the big TelephonyManager API overhaul.
        // For now, protect this call with the MODIFY_PHONE_STATE permission.)
        enforceModifyPermission();
        sendRequestAsync(CMD_SILENCE_RINGER);
    }

    /**
     * Internal implemenation of silenceRinger().
     * This should only be called from the main thread of the Phone app.
     * @see silenceRinger
     */
    private void silenceRingerInternal() {
        if ((mCM.getState() == Phone.State.RINGING)
            && mApp.notifier.isRinging()) {
            // Ringer is actually playing, so silence it.
            if (DBG) log("silenceRingerInternal: silencing...");
            mApp.notifier.silenceRinger();
        }
    }

    public boolean isOffhook() {
        return (mCM.getState() == Phone.State.OFFHOOK);
    }

    public boolean isRinging() {
        return (mCM.getState() == Phone.State.RINGING);
    }

    public boolean isIdle() {
        return (mCM.getState() == Phone.State.IDLE);
    }

    public boolean isSimPinEnabled() {
        enforceReadPermission();
        return (PhoneApp.getInstance().isSimPinEnabled());
    }

    public boolean supplyPin(String pin) {
        return (supplyPinReportResult(pin) == Phone.PIN_RESULT_SUCCESS) ? true : false;
    }

    public int supplyPinReportResult(String pin) {
        enforceModifyPermission();
        final UnlockSim checkSimPin = new UnlockSim(mPhone.getIccCard());
        checkSimPin.start();
        return checkSimPin.unlockSim(null, pin);
    }

    public boolean supplyPuk(String puk, String pin) {
        return (supplyPukReportResult(puk, pin) == Phone.PIN_RESULT_SUCCESS) ? true : false;
    }

    public int supplyPukReportResult(String puk, String pin) {
        enforceModifyPermission();
        final UnlockSim checkSimPuk = new UnlockSim(mPhone.getIccCard());
        checkSimPuk.start();
        return checkSimPuk.unlockSim(puk, pin);
    }

    /**
     * Helper thread to turn async call to {@link SimCard#supplyPin} into
     * a synchronous one.
     */
    private static class UnlockSim extends Thread {

        private final IccCard mSimCard;

        private boolean mDone = false;
        private int mResult = Phone.PIN_RESULT_SUCCESS;

        // For replies from SimCard interface
        private Handler mHandler;

        // For async handler to identify request type
        private static final int SUPPLY_PIN_COMPLETE = 100;

        public UnlockSim(IccCard simCard) {
            mSimCard = simCard;
        }

        @Override
        public void run() {
            Looper.prepare();
            synchronized (UnlockSim.this) {
                mHandler = new Handler() {
                    @Override
                    public void handleMessage(Message msg) {
                        AsyncResult ar = (AsyncResult) msg.obj;
                        switch (msg.what) {
                            case SUPPLY_PIN_COMPLETE:
                                Log.d(LOG_TAG, "SUPPLY_PIN_COMPLETE");
                                synchronized (UnlockSim.this) {
                                    if (ar.exception != null) {
                                        if (ar.exception instanceof CommandException &&
                                                ((CommandException)(ar.exception)).getCommandError()
                                                == CommandException.Error.PASSWORD_INCORRECT) {
                                            mResult = Phone.PIN_PASSWORD_INCORRECT;
                                        } else {
                                            mResult = Phone.PIN_GENERAL_FAILURE;
                                        }
                                    } else {
                                        mResult = Phone.PIN_RESULT_SUCCESS;
                                    }
                                    mDone = true;
                                    UnlockSim.this.notifyAll();
                                }
                                break;
                        }
                    }
                };
                UnlockSim.this.notifyAll();
            }
            Looper.loop();
        }

        /*
         * Use PIN or PUK to unlock SIM card
         *
         * If PUK is null, unlock SIM card with PIN
         *
         * If PUK is not null, unlock SIM card with PUK and set PIN code
         */
        synchronized int unlockSim(String puk, String pin) {

            while (mHandler == null) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Message callback = Message.obtain(mHandler, SUPPLY_PIN_COMPLETE);

            if (puk == null) {
                mSimCard.supplyPin(pin, callback);
            } else {
                mSimCard.supplyPuk(puk, pin, callback);
            }

            while (!mDone) {
                try {
                    Log.d(LOG_TAG, "wait for done");
                    wait();
                } catch (InterruptedException e) {
                    // Restore the interrupted status
                    Thread.currentThread().interrupt();
                }
            }
            Log.d(LOG_TAG, "done");
            return mResult;
        }
    }

    public void updateServiceLocation() {
        // No permission check needed here: this call is harmless, and it's
        // needed for the ServiceState.requestStateUpdate() call (which is
        // already intentionally exposed to 3rd parties.)
        mPhone.updateServiceLocation();
    }

    public boolean isRadioOn() {
        return mPhone.getServiceState().getState() != ServiceState.STATE_POWER_OFF;
    }

    public void toggleRadioOnOff() {
        enforceModifyPermission();
        mPhone.setRadioPower(!isRadioOn());
    }
    public boolean setRadio(boolean turnOn) {
        enforceModifyPermission();
        if ((mPhone.getServiceState().getState() != ServiceState.STATE_POWER_OFF) != turnOn) {
            toggleRadioOnOff();
        }
        return true;
    }

    public boolean enableDataConnectivity() {
        enforceModifyPermission();
        ConnectivityManager cm =
                (ConnectivityManager)mApp.mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        cm.setMobileDataEnabled(true);
        return true;
    }

    public int enableApnType(String type) {
        enforceModifyPermission();
        return mPhone.enableApnType(type);
    }

    public int disableApnType(String type) {
        enforceModifyPermission();
        return mPhone.disableApnType(type);
    }

    public int enableQos(QosSpec qosSpec, String type) {
        return mPhone.enableQos(qosSpec, type);
    }

    public int disableQos(int qosId) {
        return mPhone.disableQos(qosId);
    }

    public int modifyQos(int qosId, QosSpec qosSpec) {
        return mPhone.modifyQos(qosId, qosSpec);
    }

    public int suspendQos(int qosId) {
        return mPhone.suspendQos(qosId);
    }

    public int resumeQos(int qosId) {
        return mPhone.resumeQos(qosId);
    }

    public int getQosStatus(int qosId) {
        return mPhone.getQosStatus(qosId);
    }

    public boolean disableDataConnectivity() {
        enforceModifyPermission();
        ConnectivityManager cm =
                (ConnectivityManager)mApp.mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        cm.setMobileDataEnabled(false);
        return true;
    }

    public boolean isDataConnectivityPossible() {
        return mPhone.isDataConnectivityPossible();
    }

    public boolean handlePinMmi(String dialString) {
        enforceModifyPermission();
        return (Boolean) sendRequest(CMD_HANDLE_PIN_MMI, dialString);
    }

    public void cancelMissedCallsNotification() {
        enforceModifyPermission();
        mApp.notificationMgr.cancelMissedCallNotification();
    }

    public int getCallState() {
        return DefaultPhoneNotifier.convertCallState(mCM.getState());
    }

    public int getDataState() {
        return DefaultPhoneNotifier.convertDataState(mPhone.getDataConnectionState());
    }

    public int getDataActivity() {
        return DefaultPhoneNotifier.convertDataActivityState(mPhone.getDataActivityState());
    }

    public Bundle getCellLocation() {
        try {
            mApp.mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_FINE_LOCATION, null);
        } catch (SecurityException e) {
            // If we have ACCESS_FINE_LOCATION permission, skip the check for ACCESS_COARSE_LOCATION
            // A failure should throw the SecurityException from ACCESS_COARSE_LOCATION since this
            // is the weaker precondition
            mApp.mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_COARSE_LOCATION, null);
        }
        Bundle data = new Bundle();
        mPhone.getCellLocation().fillInNotifierBundle(data);
        return data;
    }

    public void enableLocationUpdates() {
        mApp.mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CONTROL_LOCATION_UPDATES, null);
        mPhone.enableLocationUpdates();
    }

    public void disableLocationUpdates() {
        mApp.mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CONTROL_LOCATION_UPDATES, null);
        mPhone.disableLocationUpdates();
    }

    @SuppressWarnings("unchecked")
    public List<NeighboringCellInfo> getNeighboringCellInfo() {
        try {
            mApp.mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.ACCESS_FINE_LOCATION, null);
        } catch (SecurityException e) {
            // If we have ACCESS_FINE_LOCATION permission, skip the check
            // for ACCESS_COARSE_LOCATION
            // A failure should throw the SecurityException from
            // ACCESS_COARSE_LOCATION since this is the weaker precondition
            mApp.mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.ACCESS_COARSE_LOCATION, null);
        }

        ArrayList<NeighboringCellInfo> cells = null;

        try {
            cells = (ArrayList<NeighboringCellInfo>) sendRequest(
                    CMD_HANDLE_NEIGHBORING_CELL, null);
        } catch (RuntimeException e) {
            Log.e(LOG_TAG, "getNeighboringCellInfo " + e);
        }

        return (List <NeighboringCellInfo>) cells;
    }

    // Gets the retry count during PIN1/PUK1 verification.
    public int getIccPin1RetryCount() {
        return mPhone.getIccCard().getIccPin1RetryCount();
    }

    public List<CellInfo> getAllCellInfo() {
        try {
            mApp.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_FINE_LOCATION, null);
        } catch (SecurityException e) {
            // If we have ACCESS_FINE_LOCATION permission, skip the check for ACCESS_COARSE_LOCATION
            // A failure should throw the SecurityException from ACCESS_COARSE_LOCATION since this
            // is the weaker precondition
            mApp.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_COARSE_LOCATION, null);
        }

        // TODO return cell info list got from mPhone
        return null;
    }

    //
    // Internal helper methods.
    //

    /**
     * Make sure the caller has the READ_PHONE_STATE permission.
     *
     * @throws SecurityException if the caller does not have the required permission
     */
    private void enforceReadPermission() {
        mApp.enforceCallingOrSelfPermission(android.Manifest.permission.READ_PHONE_STATE, null);
    }

    /**
     * Make sure the caller has the MODIFY_PHONE_STATE permission.
     *
     * @throws SecurityException if the caller does not have the required permission
     */
    private void enforceModifyPermission() {
        mApp.mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MODIFY_PHONE_STATE, null);
    }

    /**
     * Make sure the caller has the CALL_PHONE permission.
     *
     * @throws SecurityException if the caller does not have the required permission
     */
    private void enforceCallPermission() {
        mApp.mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CALL_PHONE, null);
    }


    private String createTelUrl(String number) {
        if (TextUtils.isEmpty(number)) {
            return null;
        }

        StringBuilder buf = new StringBuilder("tel:");
        buf.append(number);
        return buf.toString();
    }

    private void log(String msg) {
        Log.d(LOG_TAG, "[PhoneIntfMgr] " + msg);
    }

    public int getActivePhoneType() {
        return mPhone.getPhoneType();
    }

    public int sendOemRilRequestRaw(byte[] request, byte[] response) {
        int returnValue = 0;
        // TODO: Check Permissions of the application

        try {
            AsyncResult result = (AsyncResult)sendRequest(CMD_INVOKE_OEM_RIL_REQUEST, request);
            if(result.exception == null) {
                returnValue = 0;
                if (result.result != null) {
                    byte[] responseData = (byte[])(result.result);
                    if(responseData.length > response.length) {
                        Log.w(LOG_TAG, "Buffer to copy response too small: Response length is " +
                                responseData.length +  "bytes. Buffer Size is " +
                                response.length + "bytes.");
                    }
                    System.arraycopy(responseData, 0, response, 0, responseData.length);
                    returnValue = responseData.length;
                }

            } else {
                CommandException ex = (CommandException) result.exception;
                returnValue = ex.getCommandError().ordinal();
                if(returnValue > 0) returnValue *= -1;
            }
        } catch (RuntimeException e) {
            Log.w(LOG_TAG, "sendOemRilRequestRaw: Runtime Exception");
            returnValue = (CommandException.Error.GENERIC_FAILURE.ordinal());
            if(returnValue > 0) returnValue *= -1;
        }

        return returnValue;
    }

    public void sendOemRilRequestRawAsync(byte[] request,
            IOemHookCallback oemHookCb) {
        try {
            sendRequestAsync(CMD_INVOKE_OEM_RIL_REQUEST_ASYNC, request,
                    oemHookCb);
        } catch (RuntimeException e) {
            Log.w(LOG_TAG, "sendOemRilRequestRawAsync: Runtime Exception");
        }

    }

    /**
     * Returns the CDMA ERI icon index to display
     */
    public int getCdmaEriIconIndex() {
        return mPhone.getCdmaEriIconIndex();
    }

    /**
     * Returns the CDMA ERI icon mode,
     * 0 - ON
     * 1 - FLASHING
     */
    public int getCdmaEriIconMode() {
        return mPhone.getCdmaEriIconMode();
    }

    /**
     * Returns the CDMA ERI text,
     */
    public String getCdmaEriText() {
        return mPhone.getCdmaEriText();
    }

    /**
     * Returns true if CDMA provisioning needs to run.
     */
    public boolean needsOtaServiceProvisioning() {
        return mPhone.needsOtaServiceProvisioning();
    }

    /**
     * Returns the unread count of voicemails
     */
    public int getVoiceMessageCount() {
        return mPhone.getVoiceMessageCount();
    }

    /**
     * Returns the network type
     */
    public int getNetworkType() {
        return mPhone.getServiceState().getNetworkType();
    }

    /**
     * @return true if a ICC card is present
     */
    public boolean hasIccCard() {
        return mPhone.getIccCard().hasIccCard();
    }

    /**
     * Return if the current radio is LTE on CDMA. This
     * is a tri-state return value as for a period of time
     * the mode may be unknown.
     *
     * @return {@link Phone#LTE_ON_CDMA_UNKNOWN}, {@link Phone#LTE_ON_CDMA_FALSE}
     * or {@link PHone#LTE_ON_CDMA_TRUE}
     */
    public int getLteOnCdmaMode() {
        return mPhone.getLteOnCdmaMode();
    }

    /**
     * {@hide}
     * Modify data readiness checks performed during data call setup
     *
     * @param checkConnectivity - check for network state in service, roaming and data in roaming enabled.
     * @param checkSubscription - check for icc/nv ready and icc records loaded.
     * @param tryDataCalls - set to true to attempt data calls if data call is not already active.
     *
     */
    public void setDataReadinessChecks(boolean checkConnectivity, boolean checkSubscription,
            boolean tryDataCalls) {
        enforceModifyPermission();
        mPhone.setDataReadinessChecks(checkConnectivity, checkSubscription, tryDataCalls);
    }

    public void setPhone(Phone phone) {
        if (mPhone != null) {
            Log.d(LOG_TAG, "un=register for UNSOL OEM HOOK");
            mPhone.unSetOnUnsolOemHookExtApp(mMainThreadHandler);
        }
        mPhone = phone;
        Log.d(LOG_TAG, " Registering for UNSOL OEM HOOK Responses to deliver external apps");
        mPhone.setOnUnsolOemHookExtApp(mMainThreadHandler, EVENT_UNSOL_OEM_HOOK_EXT_APP, null);
    }
}
