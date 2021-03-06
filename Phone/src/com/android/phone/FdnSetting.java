/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.view.WindowManager;
import android.widget.Toast;
import android.content.Context;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.ITelephony;


/**
 * FDN settings UI for the Phone app.
 * Rewritten to look and behave closer to the other preferences.
 */
public class FdnSetting extends PreferenceActivity
        implements EditPinPreference.OnPinEnteredListener, DialogInterface.OnCancelListener {
	
    static final String LogTag = "FdnSetting";
    private Phone mPhone;

    /**
     * Events we handle.
     * The first is used for toggling FDN enable, the second for the PIN change.
     */
    private static final int EVENT_PIN2_ENTRY_COMPLETE = 100;
    private static final int EVENT_PIN2_CHANGE_COMPLETE = 200;

    // String keys for preference lookup
    // We only care about the pin preferences here, the manage FDN contacts
    // Preference is handled solely in xml.
    private static final String BUTTON_FDN_ENABLE_KEY = "button_fdn_enable_key";
    private static final String BUTTON_CHANGE_PIN2_KEY = "button_change_pin2_key";
    private static final String BUTTON_FDN_LIST_KEY = "button_fdn_list_key";

    private EditPinPreference mButtonEnableFDN;
    private EditPinPreference mButtonChangePin2;
    private Preference mButtonFDNList;

    // State variables
    private String mOldPin;
    private String mNewPin;
    private static final int PIN_CHANGE_OLD = 0;
    private static final int PIN_CHANGE_NEW = 1;
    private static final int PIN_CHANGE_REENTER = 2;
    private static final int PIN_CHANGE_PUK = 3;
    private int mPinChangeState;
    private boolean mSkipOldPin;    // Indicates we know that we are PUK2 blocked.

    private static final String SKIP_OLD_PIN_KEY = "skip_old_pin_key";
    private static final String PIN_CHANGE_STATE_KEY = "pin_change_state_key";
    private static final String OLD_PIN_KEY = "old_pin_key";
    private static final String NEW_PIN_KEY = "new_pin_key";
    private static final String DIALOG_MESSAGE_KEY = "dialog_message_key";
    private static final String DIALOG_PIN_ENTRY_KEY = "dialog_pin_entry_key";

    // size limits for the pin.
    private static final int MIN_PIN_LENGTH = 4;
    private static final int MAX_PIN_LENGTH = 8;

    //PIN2 Remain times Add start ----jesse----
    private TelephonyManager mTelePhoneManager;
    private int remainTimesPIN2;
    private int remainTimesPUK2;
    //PIN2 Remain times Add end

    private int mSubId = 0;

    /**
     * Delegate to the respective handlers.
     */
    public void onPinEntered(EditPinPreference preference, boolean positiveResult) {
	    Log.e(LogTag, "onPinEntered");
        if (preference == mButtonEnableFDN) {
            toggleFDNEnable(positiveResult);
        } else if (preference == mButtonChangePin2){
            updatePINChangeState(positiveResult);
        }
    }

    /**
     * Attempt to toggle FDN activation.
     */
    private void toggleFDNEnable(boolean positiveResult) {
	    Log.e(LogTag, "toggleFDNEnable");
        if (!positiveResult) {
            return;
        }

        // validate the pin first, before submitting it to the RIL for FDN enable.
        String password = mButtonEnableFDN.getText();
        if (validatePin (password, false)) {
            // get the relevant data for the icc call
            boolean isEnabled = mPhone.getIccCard().getIccFdnEnabled();
            Message onComplete = mFDNHandler.obtainMessage(EVENT_PIN2_ENTRY_COMPLETE);

            // make fdn request
            mPhone.getIccCard().setIccFdnEnabled(!isEnabled, password, onComplete);
        } else {
            // throw up error if the pin is invalid.
            displayMessage(R.string.invalidPin2);
        }

        mButtonEnableFDN.setText("");
    }

    /**
     * Attempt to change the pin.
     */
    private void updatePINChangeState(boolean positiveResult) {
        if (!positiveResult) {
            // reset the state on cancel, either to expect PUK2 or PIN2
            if (!mSkipOldPin) {
                resetPinChangeState();
            } else {
                resetPinChangeStateForPUK2();
            }
            return;
        }

        // Progress through the dialog states, generally in this order:
        //   1. Enter old pin
        //   2. Enter new pin
        //   3. Re-Enter new pin
        // While handling any error conditions that may show up in between.
        // Also handle the PUK2 entry, if it is requested.
        //
        // In general, if any invalid entries are made, the dialog re-
        // appears with text to indicate what the issue is.
        switch (mPinChangeState) {
            case PIN_CHANGE_OLD:
                mOldPin = mButtonChangePin2.getText();
                Log.e(LogTag, "updatePINChangeState PIN_CHANGE_OLD:" + mOldPin);
                mButtonChangePin2.setText("");
                // if the pin is not valid, display a message and reset the state.
                if (validatePin (mOldPin, false)) {
                    mPinChangeState = PIN_CHANGE_NEW;
                    displayPinChangeDialog();
                } else {
                    displayPinChangeDialog(R.string.invalidPin2, true);
                }
                break;
            case PIN_CHANGE_NEW:
                mNewPin = mButtonChangePin2.getText();
                Log.e(LogTag, "updatePINChangeState PIN_CHANGE_NEW:" + mNewPin);
                mButtonChangePin2.setText("");
                // if the new pin is not valid, display a message and reset the state.
                if (validatePin (mNewPin, false)) {
                    mPinChangeState = PIN_CHANGE_REENTER;
                    displayPinChangeDialog();
                } else {
                    displayPinChangeDialog(R.string.invalidPin2, true);
                }
                break;
            case PIN_CHANGE_REENTER:
            	Log.e(LogTag, "updatePINChangeState PIN_CHANGE_REENTER");
                // if the re-entered pin is not valid, display a message and reset the state.
                if (!mNewPin.equals(mButtonChangePin2.getText())) {
                    mPinChangeState = PIN_CHANGE_NEW;
                    mButtonChangePin2.setText("");
                    displayPinChangeDialog(R.string.mismatchPin2, true);
                } else {
                    // If the PIN is valid, then we either submit the change PIN request or
                    // display the PUK2 dialog if we KNOW that we're PUK2 locked.
                    mButtonChangePin2.setText("");
                    if (!mSkipOldPin) {
                        Message onComplete = mFDNHandler.obtainMessage(EVENT_PIN2_CHANGE_COMPLETE);
                        mPhone.getIccCard().changeIccFdnPassword(mOldPin, mNewPin, onComplete);
                    } else {
                        mPinChangeState = PIN_CHANGE_PUK;
                        displayPinChangeDialog();
                    }
                }
                break;
            case PIN_CHANGE_PUK: {
                    // Doh! too many incorrect requests, PUK requested.
                    // if the pin is not valid, display a message and reset the state.
                    String puk2 = mButtonChangePin2.getText();
                    Log.e(LogTag, "updatePINChangeState PIN_CHANGE_PUK:" + puk2 + " mNewPin:" + mNewPin);
                    mButtonChangePin2.setText("");
                    // make sure that the puk is valid before submitting it.
                    if (validatePin (puk2, true)) {
                        Message onComplete = mFDNHandler.obtainMessage(EVENT_PIN2_CHANGE_COMPLETE);
                        mPhone.getIccCard().supplyPuk2(puk2, mNewPin, onComplete);
                    } else {
                        displayPinChangeDialog(R.string.invalidPuk2, true);
                    }
                }
                break;
        }
    }

    /**
     * Handler for asynchronous replies from the sim.
     */
    private Handler mFDNHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {

                // when we are enabling FDN, either we are unsuccessful and display
                // a toast, or just update the UI.
                case EVENT_PIN2_ENTRY_COMPLETE: {
                	    Log.e(LogTag, "handleMessage EVENT_PIN2_ENTRY_COMPLETE");
                        AsyncResult ar = (AsyncResult) msg.obj;
                        if (ar.exception != null) {
                            // see if PUK2 is requested and alert the user accordingly.
                            CommandException ce = (CommandException) ar.exception;
                            Log.e(LogTag, "!!! " + ce.getCommandError());
                            if (ce.getCommandError() == CommandException.Error.SIM_PUK2) {
                                // make sure we set the PUK2 state so that we can skip
                                // some redundant behaviour.
                                displayMessage(R.string.fdn_enable_puk2_requested);
                                resetPinChangeStateForPUK2();
                            } else {
                            	remainTimesPIN2--;
                                displayMessage(getString(R.string.pin2_invalid) + getString(R.string.promptWordOne)
                                        + remainTimesPIN2 + getString(R.string.promptWordTwo));
                                if(remainTimesPIN2 > 0){
                                	resetPinChangeState();
                                } else {
                                	resetPinChangeStateForPUK2();
                                }
                            }
                        }
                        updateSimInfo();
                        updateEnableFDN();
                        updateFDNList();
                        Intent intent = new Intent("android.intent.action.FDN_STATE_CHANGED"+ mPhone.getPhoneId());
                        intent.putExtra("phone_id", mPhone.getPhoneId());
                        intent.putExtra("fdn_status", mPhone.getIccCard().getIccFdnEnabled());
                        sendBroadcast(intent);
                    }
                    break;

                // when changing the pin we need to pay attention to whether or not
                // the error requests a PUK (usually after too many incorrect tries)
                // Set the state accordingly.
                case EVENT_PIN2_CHANGE_COMPLETE: {
                		Log.e(LogTag, "handleMessage EVENT_PIN2_CHANGE_COMPLETE");
                        AsyncResult ar = (AsyncResult) msg.obj;
                        if (ar.exception != null) {
                            CommandException ce = (CommandException) ar.exception;
                            Log.e(LogTag, "!!! " + ce.getCommandError());
                            if (ce.getCommandError() == CommandException.Error.SIM_PUK2) {
                                // throw an alert dialog on the screen, displaying the
                                // request for a PUK2.  set the cancel listener to
                                // FdnSetting.onCancel().
                                AlertDialog a = new AlertDialog.Builder(FdnSetting.this)
                                    .setMessage(R.string.puk2_requested)
                                    .setCancelable(true)
                                    .setOnCancelListener(FdnSetting.this)
                                    .create();
                                a.getWindow().addFlags(
                                        WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                                a.show();
                            } else {
                                // set the correct error message depending upon the state.
                                if (mPinChangeState == PIN_CHANGE_PUK) {
                                	remainTimesPUK2--;
                                    displayMessage(getString(R.string.badPuk2) + getString(R.string.promptWordOne)
                                            + remainTimesPUK2 + getString(R.string.promptWordTwo));
                                } else {
                                	remainTimesPIN2--;
                                    displayMessage(R.string.badPin2);
                                    if(remainTimesPIN2 == 0){
                                    	mSkipOldPin = true;
                                    } 
                                }
                                updateEnableFDN();
                                updateFDNList();
                                // Reset the state depending upon or knowledge of the PUK state.
                                if (!mSkipOldPin) {
                                    resetPinChangeState();
                                } else {
                                    resetPinChangeStateForPUK2();
                                }
                            }
                        } else {
                            // reset to normal behaviour on successful change.
                            displayMessage(R.string.pin2_changed);
                            mSkipOldPin = false;
                            updateSimInfo();
                            resetPinChangeState();
                            updateEnableFDN();
                            updateFDNList();
                        }
                    }
                    break;
            }
        }
    };

    /**
     * Cancel listener for the PUK2 request alert dialog.
     */
    public void onCancel(DialogInterface dialog) {
        // set the state of the preference and then display the dialog.
    	Log.e(LogTag, "onCancel");
        mPinChangeState = PIN_CHANGE_PUK;
        displayPinChangeDialog(0, true);
    }

    /**
     * Display a toast for message, like the rest of the settings.
     */
    private final void displayMessage(int strId) {
    	Log.e(LogTag, "displayMessage:" + getString(strId));
        Toast.makeText(this, getString(strId), Toast.LENGTH_SHORT)
            .show();
    }

    /**
     * Display a toast for message, like the rest of the settings.
     */
    private final void displayMessage(String str) {
        Log.e(LogTag, "displayMessage:" + str);
        Toast.makeText(this, str, Toast.LENGTH_SHORT)
            .show();
    }

    /**
     * The next two functions are for updating the message field on the dialog.
     */
    private final void displayPinChangeDialog() {
        displayPinChangeDialog(0, true);
    }

    private final void displayPinChangeDialog(int strId, boolean shouldDisplay) {
        int msgId;
        String dialogMessage;
        Log.e(LogTag, "displayPinChangeDialog");
        switch (mPinChangeState) {
            case PIN_CHANGE_OLD:
                msgId = R.string.oldPin2Label;
                if (strId != 0) {
                	dialogMessage = getText(msgId) + "(" + getString(R.string.promptWordOne) + remainTimesPIN2 
                			+ getString(R.string.promptWordTwo) + ")" + "\n" + getText(strId);
                } else {
                	dialogMessage = getText(msgId) + "(" + getString(R.string.promptWordOne) 
            				+ remainTimesPIN2 + getString(R.string.promptWordTwo) + ")";
                }
                break;
            case PIN_CHANGE_NEW:
                msgId = R.string.newPin2Label;
                if (strId != 0) {
                	dialogMessage = getText(msgId) + "\n" + getText(strId);
                } else {
                	dialogMessage = getText(msgId) + "";
                }
                break;
            case PIN_CHANGE_REENTER:
                msgId = R.string.confirmPin2Label;
                if (strId != 0) {
                	dialogMessage = getText(msgId) + "\n" + getText(strId);
                } else {
                	dialogMessage = getText(msgId) + "";
                }
                break;
            case PIN_CHANGE_PUK:
            default:
                msgId = R.string.label_puk2_code;
                if (strId != 0) {
                	dialogMessage = getText(msgId) + "(" + getString(R.string.promptWordOne) + remainTimesPUK2 
                			+ getString(R.string.promptWordTwo) + ")" + "\n" + getText(strId);
                } else {
                	dialogMessage = getText(msgId) + "(" + getString(R.string.promptWordOne) 
            				+ remainTimesPUK2 + getString(R.string.promptWordTwo) + ")";
                }
                break;
        }
        Log.e(LogTag, "dialogMessage:" + dialogMessage);
        mButtonChangePin2.setDialogMessage(dialogMessage);
        
        // only display if requested.
        if (shouldDisplay) {
            mButtonChangePin2.showPinDialog();
        }
    }

    /**
     * Reset the state of the pin change dialog.
     */
    private final void resetPinChangeState() {
    	Log.e(LogTag, "resetPinChangeState");
        mPinChangeState = PIN_CHANGE_OLD;
        mButtonChangePin2.setSummary(R.string.sum_fdn_change_pin);
        displayPinChangeDialog(0, false);
        mOldPin = mNewPin = "";
    }

    /**
     * Reset the state of the pin change dialog solely for PUK2 use.
     */
    private final void resetPinChangeStateForPUK2() {
    	Log.e(LogTag, "resetPinChangeStateForPUK2");
        mPinChangeState = PIN_CHANGE_NEW;
        mButtonChangePin2.setSummary(R.string.puk2_requested);
        displayPinChangeDialog(0, false);
        mOldPin = mNewPin = "";
        mSkipOldPin = true;
    }

    /**
     * Validate the pin entry.
     *
     * @param pin This is the pin to validate
     * @param isPuk Boolean indicating whether we are to treat
     * the pin input as a puk.
     */
    private boolean validatePin(String pin, boolean isPUK) {
    	Log.e(LogTag, "validatePin");
        // for pin, we have 4-8 numbers, or puk, we use only 8.
        int pinMinimum = isPUK ? MAX_PIN_LENGTH : MIN_PIN_LENGTH;

        // check validity
        if (pin == null || pin.length() < pinMinimum || pin.length() > MAX_PIN_LENGTH) {
            return false;
        } else {
            return true;
        }
    }

    /**
     * Reflect the updated FDN state in the UI.
     */
    private void updateEnableFDN() {
    	//PIN2 Remain times Add start ----jesse----
    	if(remainTimesPIN2 == 0){
    		mButtonEnableFDN.setEnabled(false);
    		Log.e(LogTag, "updateEnableFDN Enable false");
    	} else {
    		mButtonEnableFDN.setEnabled(true);
	        String dialogMessage = getString(R.string.enter_pin2_text) + "(" + getString(R.string.promptWordOne) 
					+ remainTimesPIN2 + getString(R.string.promptWordTwo) + ")";
	        mButtonEnableFDN.setDialogMessage(dialogMessage);
		    Log.e(LogTag, "updateEnableFDN " + dialogMessage);
    	}
	    //PIN2 Remain times Add end
        if (mPhone.getIccCard().getIccFdnEnabled()) {
            Log.d(LogTag, "mPhone.getIccCard().getIccFdnEnabled()=true");
            mButtonEnableFDN.setTitle(R.string.enable_fdn_ok);
            mButtonEnableFDN.setSummary(R.string.fdn_enabled);
            mButtonEnableFDN.setDialogTitle(R.string.disable_fdn);
        } else {
            Log.d(LogTag, "mPhone.getIccCard().getIccFdnEnabled()=false");
            mButtonEnableFDN.setTitle(R.string.disable_fdn_ok);
            mButtonEnableFDN.setSummary(R.string.fdn_disabled);
            mButtonEnableFDN.setDialogTitle(R.string.enable_fdn);
        }
        //Lino modify for NEWMS00134894 begin 2011-10-28
        if(remainTimesPUK2 <= 0){
        	mButtonChangePin2.setEnabled(false);
        }
        //Lino modify for NEWMS00134895 end 2011-10-28
    }
    
    /**
     * Updated FDN List item state in the UI.
     */
    private void updateFDNList() {
    	Log.e(LogTag, "updateFDNList");
        if((remainTimesPIN2 == 0) || (!mPhone.getIccCard().getIccFdnEnabled())){
    		mButtonFDNList.setEnabled(false);
    	} else {
    		mButtonFDNList.setEnabled(true);
    	}
    }

    /**
     * Update pin and puk remain times in sim.
     */
    private void updateSimInfo() {
        mTelePhoneManager = (TelephonyManager)getSystemService(PhoneFactory.getServiceName(Context.TELEPHONY_SERVICE, mSubId));
        remainTimesPIN2 = mTelePhoneManager.getRemainTimes(TelephonyManager.UNLOCK_PIN2);
        remainTimesPUK2 = mTelePhoneManager.getRemainTimes(TelephonyManager.UNLOCK_PUK2);
        Log.e(LogTag, "updateSimInfo remainTimesPIN2:" + remainTimesPIN2 + " remainTimesPUK2:" + remainTimesPUK2);
    }
    
    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        addPreferencesFromResource(R.xml.fdn_setting);
        mSubId = getIntent().getIntExtra(CallSettingOptions.SUB_ID, 0);
        if (TelephonyManager.getPhoneCount() > 1) {
            if(mSubId == 0) {
                setTitle(getResources().getString(R.string.sim1) + getResources().getString(R.string.fdn));
            }else if(mSubId == 1) {
                setTitle(getResources().getString(R.string.sim2) + getResources().getString(R.string.fdn));
            }
        }
        Log.e(LogTag, "onCreate");
//        mPhone = PhoneFactory.getDefaultPhone();
        //for ds
        mPhone = PhoneApp.getInstance().getPhone(mSubId);

        updateSimInfo();
        
        //get UI object references
        PreferenceScreen prefSet = getPreferenceScreen();
        mButtonEnableFDN = (EditPinPreference) prefSet.findPreference(BUTTON_FDN_ENABLE_KEY);
        mButtonChangePin2 = (EditPinPreference) prefSet.findPreference(BUTTON_CHANGE_PIN2_KEY);
        mButtonFDNList = (Preference) prefSet.findPreference(BUTTON_FDN_LIST_KEY);
        mButtonFDNList.getIntent().putExtra(CallSettingOptions.SUB_ID, mSubId);

        //assign click listener and update state
        mButtonEnableFDN.setOnPinEnteredListener(this);
        updateEnableFDN();

        mButtonChangePin2.setOnPinEnteredListener(this);

        // Only reset the pin change dialog if we're not in the middle of changing it.
        if (icicle == null) {
        	if(remainTimesPIN2 > 0){
        		resetPinChangeState();
        	} else {
        		resetPinChangeStateForPUK2();
        	}
        } else {
            mSkipOldPin = icicle.getBoolean(SKIP_OLD_PIN_KEY);
            mPinChangeState = icicle.getInt(PIN_CHANGE_STATE_KEY);
            mOldPin = icicle.getString(OLD_PIN_KEY);
            mNewPin = icicle.getString(NEW_PIN_KEY);
            mButtonChangePin2.setDialogMessage(icicle.getString(DIALOG_MESSAGE_KEY));
            mButtonChangePin2.setText(icicle.getString(DIALOG_PIN_ENTRY_KEY));
            Log.e(LogTag, "onCreate dialogMessage2:" + icicle.getString(DIALOG_MESSAGE_KEY));
        }
        
        updateFDNList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.e(LogTag, "onResume");
//        mPhone = PhoneFactory.getDefaultPhone();
        mPhone = PhoneApp.getInstance().getPhone(mSubId);
        updateEnableFDN();
    }
	
	//add by niezhong for NEWMS00154369 begin
	@Override
	protected void onRestart() {
		super.onRestart();
		updateSimInfo();
		if(remainTimesPIN2 > 0){
    		resetPinChangeState();
    	} else {
    		resetPinChangeStateForPUK2();
    	}
		updateEnableFDN();
		updateFDNList();
	}
    //add by niezhong for NEWMS00154369 end
	
    /**
     * Save the state of the pin change.
     */
    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        Log.e(LogTag, "onSaveInstanceState");
        out.putBoolean(SKIP_OLD_PIN_KEY, mSkipOldPin);
        out.putInt(PIN_CHANGE_STATE_KEY, mPinChangeState);
        out.putString(OLD_PIN_KEY, mOldPin);
        out.putString(NEW_PIN_KEY, mNewPin);
        out.putString(DIALOG_MESSAGE_KEY, mButtonChangePin2.getDialogMessage().toString());
        out.putString(DIALOG_PIN_ENTRY_KEY, mButtonChangePin2.getText());
    }
}

