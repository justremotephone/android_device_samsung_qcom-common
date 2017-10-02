/*
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
 *
 * Initial implementation by thomas.lehner@justremotephone.com 2017 using
 * parts of AOSP.
 *
 */

package com.rildservice;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.radio.V1_0.AppStatus;
import android.hardware.radio.V1_0.Call;
import android.hardware.radio.V1_0.CardStatus;
import android.hardware.radio.V1_0.CdmaSignalInfoRecord;
import android.hardware.radio.V1_0.CellIdentityGsm;
import android.hardware.radio.V1_0.CellIdentityLte;
import android.hardware.radio.V1_0.CellIdentityTdscdma;
import android.hardware.radio.V1_0.CellIdentityWcdma;
import android.hardware.radio.V1_0.CellInfo;
import android.hardware.radio.V1_0.DataRegStateResult;
import android.hardware.radio.V1_0.IccIoResult;
import android.hardware.radio.V1_0.LastCallFailCause;
import android.hardware.radio.V1_0.LastCallFailCauseInfo;
import android.hardware.radio.V1_0.LceStatusInfo;
import android.hardware.radio.V1_0.SendSmsResult;
import android.hardware.radio.V1_0.SignalStrength;
import android.hardware.radio.V1_0.VoiceRegStateResult;
import android.os.RemoteException;
import android.telephony.Rlog;

import android.hardware.radio.V1_0.CallForwardInfo;
import android.hardware.radio.V1_0.Carrier;
import android.hardware.radio.V1_0.CarrierRestrictions;
import android.hardware.radio.V1_0.CdmaBroadcastSmsConfigInfo;
import android.hardware.radio.V1_0.CdmaSmsAck;
import android.hardware.radio.V1_0.CdmaSmsMessage;
import android.hardware.radio.V1_0.CdmaSmsWriteArgs;
import android.hardware.radio.V1_0.CellInfoCdma;
import android.hardware.radio.V1_0.CellInfoGsm;
import android.hardware.radio.V1_0.CellInfoLte;
import android.hardware.radio.V1_0.CellInfoType;
import android.hardware.radio.V1_0.CellInfoWcdma;
import android.hardware.radio.V1_0.DataProfileInfo;
import android.hardware.radio.V1_0.Dial;
import android.hardware.radio.V1_0.GsmBroadcastSmsConfigInfo;
import android.hardware.radio.V1_0.GsmSmsMessage;
import android.hardware.radio.V1_0.HardwareConfigModem;
import android.hardware.radio.V1_0.IRadio;
import android.hardware.radio.V1_0.IRadioIndication;
import android.hardware.radio.V1_0.IRadioResponse;
import android.hardware.radio.V1_0.IccIo;
import android.hardware.radio.V1_0.ImsSmsMessage;
import android.hardware.radio.V1_0.LceDataInfo;
import android.hardware.radio.V1_0.MvnoType;
import android.hardware.radio.V1_0.NvWriteItem;
import android.hardware.radio.V1_0.RadioCapability;
import android.hardware.radio.V1_0.RadioError;
import android.hardware.radio.V1_0.RadioIndicationType;
import android.hardware.radio.V1_0.RadioResponseInfo;
import android.hardware.radio.V1_0.RadioResponseType;
import android.hardware.radio.V1_0.ResetNvType;
import android.hardware.radio.V1_0.SelectUiccSub;
import android.hardware.radio.V1_0.SetupDataCallResult;
import android.hardware.radio.V1_0.SimApdu;
import android.hardware.radio.V1_0.SmsWriteArgs;
import android.hardware.radio.V1_0.UusInfo;
import android.hardware.radio.deprecated.V1_0.IOemHook;
import android.hardware.radio.deprecated.V1_0.IOemHookIndication;
import android.hardware.radio.deprecated.V1_0.IOemHookResponse;

import static com.android.internal.telephony.RILConstants.*;
import static com.android.internal.telephony.RILConstants.RIL_UNSOL_RESPONSE_NETWORK_STATE_CHANGED;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import android.os.Parcel;

import com.android.internal.telephony.RILConstants;

import java.io.IOException;
import java.io.InputStream;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;

public class RildService extends BroadcastReceiver {

    private static final boolean DEBUG = true;
    private static final String TAG = "RildService";

    private int mInstanceId = 0;
    private LocalSocket mSocket;
    private RILReceiver mReceiver;
    private Thread mReceiverThread;

    private IRadioResponse mRadioResponse;
    private IRadioIndication mRadioIndication;

    private IOemHookResponse mOemHookResponse;
    private IOemHookIndication mOemHookIndication;

    private Object sendSyncObj = new Object();
    private HashMap<Integer, Integer> serialToRequestCode = new HashMap<Integer, Integer>();
    private byte[] dataLength = new byte[4];

    // match with constant in ril.cpp
    static final int RIL_MAX_COMMAND_BYTES = (8 * 1024);
    static final int RESPONSE_SOLICITED = 0;
    static final int RESPONSE_UNSOLICITED = 1;
    static final int RESPONSE_SOLICITED_ACK = 2;
    static final int RESPONSE_SOLICITED_ACK_EXP = 3;
    static final int RESPONSE_UNSOLICITED_ACK_EXP = 4;

    static final String[] SOCKET_NAME_RIL = {"rild", "rild2", "rild3"};

    static final int SOCKET_OPEN_RETRY_MILLIS = 4 * 1000;


    /**
     * Reads in a single RIL message off the wire. A RIL message consists
     * of a 4-byte little-endian length and a subsequent series of bytes.
     * The final message (length header omitted) is read into
     * <code>buffer</code> and the length of the final message (less header)
     * is returned. A return value of -1 indicates end-of-stream.
     *
     * @param is     non-null; Stream to read from
     * @param buffer Buffer to fill in. Must be as large as maximum
     *               message size, or an ArrayOutOfBounds exception will be thrown.
     * @return Length of message less header, or -1 on end of stream.
     * @throws IOException
     */
    private static int readRilMessage(InputStream is, byte[] buffer)
            throws IOException {
        int countRead;
        int offset;
        int remaining;
        int messageLength;

        // First, read in the length of the message
        offset = 0;
        remaining = 4;
        do {

            countRead = is.read(buffer, offset, remaining);

            if (countRead < 0) {
                Rlog.e(TAG, "Hit EOS reading message length");
                return -1;
            }

            offset += countRead;
            remaining -= countRead;
        } while (remaining > 0);

        messageLength = ((buffer[0] & 0xff) << 24)
                | ((buffer[1] & 0xff) << 16)
                | ((buffer[2] & 0xff) << 8)
                | (buffer[3] & 0xff);

        // Then, re-use the buffer and read in the message itself
        offset = 0;
        remaining = messageLength;
        do {
            countRead = is.read(buffer, offset, remaining);

            if (countRead < 0) {
                Rlog.e(TAG, "Hit EOS reading message.  messageLength=" + messageLength
                        + " remaining=" + remaining);
                return -1;
            }

            offset += countRead;
            remaining -= countRead;
        } while (remaining > 0);

        return messageLength;
    }

    class RILReceiver implements Runnable {
        byte[] buffer;

        RILReceiver() {
            buffer = new byte[RIL_MAX_COMMAND_BYTES];
        }

        @Override
        public void
        run() {
            int retryCount = 0;
            String rilSocket = "rild";

            try {
                for (; ; ) {
                    LocalSocket s = null;
                    LocalSocketAddress l;

                    if (mInstanceId == 0) {
                        rilSocket = SOCKET_NAME_RIL[0];
                    } else {
                        rilSocket = SOCKET_NAME_RIL[mInstanceId];
                    }

                    try {
                        s = new LocalSocket();
                        l = new LocalSocketAddress(rilSocket,
                                LocalSocketAddress.Namespace.RESERVED);
                        s.connect(l);
                    } catch (IOException ex) {
                        try {
                            if (s != null) {
                                s.close();
                            }
                        } catch (IOException ex2) {
                            //ignore failure to close after failure to connect
                        }

                        // don't print an error message after the the first time
                        // or after the 8th time

                        if (retryCount == 8) {
                            Rlog.e(TAG,
                                    "Couldn't find '" + rilSocket
                                            + "' socket after " + retryCount
                                            + " times, continuing to retry silently" + ex);
                        } else if (retryCount >= 0 && retryCount < 8) {
                            Rlog.i(TAG,
                                    "Couldn't find '" + rilSocket
                                            + "' socket; retrying after timeout: " + ex);
                        }

                        try {
                            Thread.sleep(SOCKET_OPEN_RETRY_MILLIS);
                        } catch (InterruptedException er) {
                        }

                        retryCount++;
                        continue;
                    }

                    retryCount = 0;

                    mSocket = s;
                    Rlog.i(TAG, "(" + mInstanceId + ") Connected to '"
                            + rilSocket + "' socket");

                    int length = 0;
                    try {
                        InputStream is = mSocket.getInputStream();

                        for (; ; ) {
                            Parcel p;

                            length = readRilMessage(is, buffer);

                            if (length < 0) {
                                // End-of-stream reached
                                break;
                            }

                            p = Parcel.obtain();
                            p.unmarshall(buffer, 0, length);
                            p.setDataPosition(0);

                            //RRlog.v(RILJ_LOG_TAG, "Read packet: " + length + " bytes");

                            processResponse(p);
                            p.recycle();
                        }
                    } catch (java.io.IOException ex) {
                        Rlog.i(TAG, "'" + rilSocket + "' socket closed",
                                ex);
                    } catch (Throwable tr) {
                        Rlog.e(TAG, "Uncaught exception read length=" + length +
                                "Exception:" + tr.toString());
                    }

                    Rlog.i(TAG, "(" + mInstanceId + ") Disconnected from '" + rilSocket
                            + "' socket");

                    // setRadioState (RadioState.RADIO_UNAVAILABLE);

                    try {
                        mSocket.close();
                    } catch (IOException ex) {
                    }

                    mSocket = null;
                    //RILRequest.resetSerial();

                    // Clear request list on close
                    //clearRequestList(RADIO_NOT_AVAILABLE, false);
                }
            } catch (Throwable tr) {
                Rlog.e(TAG, "Uncaught exception", tr);
            }

            /* We're disconnected so we don't know the ril version */
            //notifyRegistrantsRilConnectionChanged(-1);
        }
    }

    private void
    processResponse(Parcel p) {
        int type;

        type = p.readInt();

        if (type == RESPONSE_UNSOLICITED || type == RESPONSE_UNSOLICITED_ACK_EXP) {
            Rlog.d(TAG, "processResponse:  RESPONSE_UNSOLICITED || RESPONSE_UNSOLICITED_ACK_EXP");
            processUnsolicited(p, type);
        } else if (type == RESPONSE_SOLICITED || type == RESPONSE_SOLICITED_ACK_EXP) {
            Rlog.d(TAG, "processResponse:  RESPONSE_SOLICITED || RESPONSE_SOLICITED_ACK_EXP");
            processSolicited(p, type);

        } else if (type == RESPONSE_SOLICITED_ACK) {
            int serial;
            serial = p.readInt();

            Rlog.e(TAG, "processResponse:  RESPONSE_SOLICITED_ACK");

            //  RILRequest rr;
            //  synchronized (mRequestList) {
            //      rr = mRequestList.get(serial);
            //  }
            //  if (rr == null) {
            //      RRlog.w(RILJ_LOG_TAG, "Unexpected solicited ack response! sn: " + serial);
            //  } else {
            //      decrementWakeLock(rr);
            //      if (RILJ_LOGD) {
            //          riljLog(rr.serialString() + " Ack < " + requestToString(rr.mRequest));
            //      }
            //  }
        }
    }

    private String STRING_NULL_HANDLED(String val) {
        if (val == null)
            return "";

        return val;
    }

    private int ATOI_NULL_HANDLED_DEF(String val, int defVal) {
        try {
            if (val == null)
                return defVal;

            return Integer.parseInt(val);
        } catch (java.lang.NumberFormatException ex) {
            return defVal;
        }
    }

    private int ATOI_NULL_HANDLED(String val) {
        return ATOI_NULL_HANDLED_DEF(val, 0);
    }

    private String STRING_TORIL_NULL_HANDLED(String val) {
        if (val != null && val.length() == 0)
            return null;

        return val;
    }

    /**
     * Available radio technologies for GSM, UMTS and CDMA.
     * Duplicates the constants from hardware/radio/include/ril.h
     * This should only be used by agents working with the ril.  Others
     * should use the equivalent TelephonyManager.NETWORK_TYPE_*
     */
    /**
     * @hide
     */
    public static final int RIL_RADIO_TECHNOLOGY_UNKNOWN = 0;
    /**
     * @hide
     */
    public static final int RIL_RADIO_TECHNOLOGY_GPRS = 1;
    /**
     * @hide
     */
    public static final int RIL_RADIO_TECHNOLOGY_EDGE = 2;
    /**
     * @hide
     */
    public static final int RIL_RADIO_TECHNOLOGY_UMTS = 3;
    /**
     * @hide
     */
    public static final int RIL_RADIO_TECHNOLOGY_IS95A = 4;
    /**
     * @hide
     */
    public static final int RIL_RADIO_TECHNOLOGY_IS95B = 5;
    /**
     * @hide
     */
    public static final int RIL_RADIO_TECHNOLOGY_1xRTT = 6;
    /**
     * @hide
     */
    public static final int RIL_RADIO_TECHNOLOGY_EVDO_0 = 7;
    /**
     * @hide
     */
    public static final int RIL_RADIO_TECHNOLOGY_EVDO_A = 8;
    /**
     * @hide
     */
    public static final int RIL_RADIO_TECHNOLOGY_HSDPA = 9;
    /**
     * @hide
     */
    public static final int RIL_RADIO_TECHNOLOGY_HSUPA = 10;
    /**
     * @hide
     */
    public static final int RIL_RADIO_TECHNOLOGY_HSPA = 11;
    /**
     * @hide
     */
    public static final int RIL_RADIO_TECHNOLOGY_EVDO_B = 12;
    /**
     * @hide
     */
    public static final int RIL_RADIO_TECHNOLOGY_EHRPD = 13;
    /**
     * @hide
     */
    public static final int RIL_RADIO_TECHNOLOGY_LTE = 14;
    /**
     * @hide
     */
    public static final int RIL_RADIO_TECHNOLOGY_HSPAP = 15;
    /**
     * GSM radio technology only supports voice. It does not support data.
     *
     * @hide
     */
    public static final int RIL_RADIO_TECHNOLOGY_GSM = 16;
    /**
     * @hide
     */
    public static final int RIL_RADIO_TECHNOLOGY_TD_SCDMA = 17;
    /**
     * IWLAN
     *
     * @hide
     */
    public static final int RIL_RADIO_TECHNOLOGY_IWLAN = 18;
    /**
     * LTE_CA
     *
     * @hide
     */
    public static final int RIL_RADIO_TECHNOLOGY_LTE_CA = 19;

    int getCellInfoTypeRadioTechnology(String rat) {

        if (rat == null) {
            return CellInfoType.NONE;
        }

        int radioTech = Integer.parseInt(rat);

        switch (radioTech) {

            case RIL_RADIO_TECHNOLOGY_GPRS:
            case RIL_RADIO_TECHNOLOGY_EDGE:
            case RIL_RADIO_TECHNOLOGY_GSM: {
                return CellInfoType.GSM;
            }

            case RIL_RADIO_TECHNOLOGY_UMTS:
            case RIL_RADIO_TECHNOLOGY_HSDPA:
            case RIL_RADIO_TECHNOLOGY_HSUPA:
            case RIL_RADIO_TECHNOLOGY_HSPA:
            case RIL_RADIO_TECHNOLOGY_HSPAP: {
                return CellInfoType.WCDMA;
            }

            case RIL_RADIO_TECHNOLOGY_IS95A:
            case RIL_RADIO_TECHNOLOGY_IS95B:
            case RIL_RADIO_TECHNOLOGY_1xRTT:
            case RIL_RADIO_TECHNOLOGY_EVDO_0:
            case RIL_RADIO_TECHNOLOGY_EVDO_A:
            case RIL_RADIO_TECHNOLOGY_EVDO_B:
            case RIL_RADIO_TECHNOLOGY_EHRPD: {
                return CellInfoType.CDMA;
            }

            case RIL_RADIO_TECHNOLOGY_LTE:
            case RIL_RADIO_TECHNOLOGY_LTE_CA: {
                return CellInfoType.LTE;
            }

            case RIL_RADIO_TECHNOLOGY_TD_SCDMA: {
                return CellInfoType.TD_SCDMA;
            }

            default: {
                break;
            }
        }

        return CellInfoType.NONE;

    }

    int convertResponseStringEntryToInt(String[] response, int index, int numStrings) {
        if ((response != null) && (numStrings > index) && (response[index] != null)) {
            return Integer.parseInt(response[index]);
        }

        return -1;
    }

    int convertResponseHexStringEntryToInt(String[] response, int index, int numStrings) {

        if ((response != null) && (numStrings > index) && (response[index] != null)) {
            return Integer.parseInt(response[index], 16);
        }

        return -1;
    }

    /* Fill Cell Identity info from Voice Registration State Response.
 * This fucntion is applicable only for RIL Version < 15.
 * Response is a  "char **".
 * First and Second entries are in hex string format
 * and rest are integers represented in ascii format. */
    void fillCellIdentityFromVoiceRegStateResponseString(android.hardware.radio.V1_0.CellIdentity cellIdentity, int numStrings, String[] response) {

        cellIdentity.cellInfoType = getCellInfoTypeRadioTechnology(response[3]);
        switch (cellIdentity.cellInfoType) {

            case CellInfoType.GSM: {
                cellIdentity.cellIdentityGsm.add(new CellIdentityGsm());

            /* valid LAC are hexstrings in the range 0x0000 - 0xffff */
                cellIdentity.cellIdentityGsm.get(0).lac =
                        convertResponseHexStringEntryToInt(response, 1, numStrings);

            /* valid CID are hexstrings in the range 0x00000000 - 0xffffffff */
                cellIdentity.cellIdentityGsm.get(0).cid =
                        convertResponseHexStringEntryToInt(response, 2, numStrings);
                break;
            }

            case CellInfoType.WCDMA: {
                cellIdentity.cellIdentityWcdma.add(new CellIdentityWcdma());

            /* valid LAC are hexstrings in the range 0x0000 - 0xffff */
                cellIdentity.cellIdentityWcdma.get(0).lac =
                        convertResponseHexStringEntryToInt(response, 1, numStrings);

            /* valid CID are hexstrings in the range 0x00000000 - 0xffffffff */
                cellIdentity.cellIdentityWcdma.get(0).cid =
                        convertResponseHexStringEntryToInt(response, 2, numStrings);
                cellIdentity.cellIdentityWcdma.get(0).psc =
                        convertResponseStringEntryToInt(response, 14, numStrings);
                break;
            }

            case CellInfoType.TD_SCDMA: {
            /* valid LAC are hexstrings in the range 0x0000 - 0xffff */
                cellIdentity.cellIdentityTdscdma.get(0).lac =
                        convertResponseHexStringEntryToInt(response, 1, numStrings);

            /* valid CID are hexstrings in the range 0x00000000 - 0xffffffff */
                cellIdentity.cellIdentityTdscdma.get(0).cid =
                        convertResponseHexStringEntryToInt(response, 2, numStrings);
                break;
            }

            case CellInfoType.CDMA: {
                cellIdentity.cellIdentityCdma.get(0).baseStationId =
                        convertResponseStringEntryToInt(response, 4, numStrings);
                cellIdentity.cellIdentityCdma.get(0).longitude =
                        convertResponseStringEntryToInt(response, 5, numStrings);
                cellIdentity.cellIdentityCdma.get(0).latitude =
                        convertResponseStringEntryToInt(response, 6, numStrings);
                cellIdentity.cellIdentityCdma.get(0).systemId =
                        convertResponseStringEntryToInt(response, 8, numStrings);
                cellIdentity.cellIdentityCdma.get(0).networkId =
                        convertResponseStringEntryToInt(response, 9, numStrings);
                break;
            }

            case CellInfoType.LTE: {
            /* valid TAC are hexstrings in the range 0x0000 - 0xffff */
                cellIdentity.cellIdentityLte.get(0).tac =
                        convertResponseHexStringEntryToInt(response, 1, numStrings);

            /* valid CID are hexstrings in the range 0x00000000 - 0xffffffff */
                cellIdentity.cellIdentityLte.get(0).ci =
                        convertResponseHexStringEntryToInt(response, 2, numStrings);
                break;
            }

            default: {
                break;
            }
        }
    }

    private VoiceRegStateResult readVoiceRegStateResult(Parcel p) throws RemoteException {

        String[] resp = p.readStringArray();

        if (resp == null && resp.length != 15)
            throw new RemoteException("invalid arguments");

        VoiceRegStateResult voiceRegResponse = new VoiceRegStateResult();

        voiceRegResponse.regState = ATOI_NULL_HANDLED_DEF(resp[0], 4);
        voiceRegResponse.rat = ATOI_NULL_HANDLED(resp[3]);
        voiceRegResponse.cssSupported = ATOI_NULL_HANDLED_DEF(resp[7], 0) != 0;
        voiceRegResponse.roamingIndicator = ATOI_NULL_HANDLED(resp[10]);
        voiceRegResponse.systemIsInPrl = ATOI_NULL_HANDLED_DEF(resp[11], 0);
        voiceRegResponse.defaultRoamingIndicator = ATOI_NULL_HANDLED_DEF(resp[12], 0);
        voiceRegResponse.reasonForDenial = ATOI_NULL_HANDLED_DEF(resp[13], 0);
        fillCellIdentityFromVoiceRegStateResponseString(voiceRegResponse.cellIdentity, resp.length, resp);

        return voiceRegResponse;
    }

    /* Fill Cell Identity info from Data Registration State Response.
 * This fucntion is applicable only for RIL Version < 15.
 * Response is a  "char **".
 * First and Second entries are in hex string format
 * and rest are integers represented in ascii format. */
    void fillCellIdentityFromDataRegStateResponseString(android.hardware.radio.V1_0.CellIdentity cellIdentity,
                                                        int numStrings, String[] response) {

        cellIdentity.cellInfoType = getCellInfoTypeRadioTechnology(response[3]);
        switch (cellIdentity.cellInfoType) {
            case CellInfoType.GSM: {
                cellIdentity.cellIdentityGsm.add(new CellIdentityGsm());

            /* valid LAC are hexstrings in the range 0x0000 - 0xffff */
                cellIdentity.cellIdentityGsm.get(0).lac =
                        convertResponseHexStringEntryToInt(response, 1, numStrings);

            /* valid CID are hexstrings in the range 0x00000000 - 0xffffffff */
                cellIdentity.cellIdentityGsm.get(0).cid =
                        convertResponseHexStringEntryToInt(response, 2, numStrings);
                break;
            }
            case CellInfoType.WCDMA: {
                cellIdentity.cellIdentityWcdma.add(new CellIdentityWcdma());

            /* valid LAC are hexstrings in the range 0x0000 - 0xffff */
                cellIdentity.cellIdentityWcdma.get(0).lac =
                        convertResponseHexStringEntryToInt(response, 1, numStrings);

            /* valid CID are hexstrings in the range 0x00000000 - 0xffffffff */
                cellIdentity.cellIdentityWcdma.get(0).cid =
                        convertResponseHexStringEntryToInt(response, 2, numStrings);
                break;
            }
            case CellInfoType.TD_SCDMA: {
                cellIdentity.cellIdentityTdscdma.add(new CellIdentityTdscdma());

            /* valid LAC are hexstrings in the range 0x0000 - 0xffff */
                cellIdentity.cellIdentityTdscdma.get(0).lac =
                        convertResponseHexStringEntryToInt(response, 1, numStrings);

            /* valid CID are hexstrings in the range 0x00000000 - 0xffffffff */
                cellIdentity.cellIdentityTdscdma.get(0).cid =
                        convertResponseHexStringEntryToInt(response, 2, numStrings);
                break;
            }
            case CellInfoType.LTE: {
                cellIdentity.cellIdentityLte.add(new CellIdentityLte());

                cellIdentity.cellIdentityLte.get(0).tac =
                        convertResponseStringEntryToInt(response, 6, numStrings);
                cellIdentity.cellIdentityLte.get(0).pci =
                        convertResponseStringEntryToInt(response, 7, numStrings);
                cellIdentity.cellIdentityLte.get(0).ci =
                        convertResponseStringEntryToInt(response, 8, numStrings);
                break;
            }
            default: {
                break;
            }
        }
    }

    private DataRegStateResult readDataRegStateResult(Parcel p) throws RemoteException {

        String[] resp = p.readStringArray();

        if (resp == null && resp.length != 6 && resp.length != 11)
            throw new RemoteException("invalid arguments");

        DataRegStateResult dataRegResponse = new DataRegStateResult();

        dataRegResponse.regState = ATOI_NULL_HANDLED_DEF(resp[0], 4);
        dataRegResponse.rat = ATOI_NULL_HANDLED_DEF(resp[3], 0);
        dataRegResponse.reasonDataDenied = ATOI_NULL_HANDLED(resp[4]);
        dataRegResponse.maxDataCalls = ATOI_NULL_HANDLED_DEF(resp[5], 1);
        fillCellIdentityFromDataRegStateResponseString(dataRegResponse.cellIdentity, resp.length, resp);

        return dataRegResponse;
    }

    private String readBaseBandVersion(Parcel p) {

        String baseBandVersion = STRING_NULL_HANDLED(p.readString());
        return baseBandVersion;
    }

    private ArrayList<Byte> readOemRaw(Parcel p) {

        byte[] buffer = p.createByteArray();

        ArrayList<Byte> data = new ArrayList<Byte>();
        for (int i = 0; i < buffer.length; i++)
            data.add(buffer[i]);

        return data;
    }

    private ArrayList<String> readOemStrings(Parcel p) {

        String[] buffer = p.createStringArray();

        ArrayList<String> data = new ArrayList<String>();
        for (int i = 0; i < buffer.length; i++)
            data.add(buffer[i]);

        return data;
    }

    private void handleGetOperatorResponse(IRadioResponse radioResponse, RadioResponseInfo responseInfo, Parcel p) throws RemoteException {
        String[] resp = p.readStringArray();

        if (resp == null && resp.length < 3)
            throw new RemoteException("invalid arguments");

        String longName = STRING_NULL_HANDLED(resp[0]);
        String shortName = STRING_NULL_HANDLED(resp[1]);
        String numeric = STRING_NULL_HANDLED(resp[2]);

        radioResponse.getOperatorResponse(responseInfo, longName, shortName, numeric);
    }

    private void handleRadioStateChanged(IRadioIndication radioIndication, int indicationType, Parcel p) throws RemoteException {

        int state = p.readInt();

        int radioState = 0;

        /* RIL_RadioState ril.h */
        switch (state) {
            case 0:
                radioState = android.hardware.radio.V1_0.RadioState.OFF;
                break;
            case 1:
                radioState = android.hardware.radio.V1_0.RadioState.UNAVAILABLE;
                break;
            case 10:
                radioState = android.hardware.radio.V1_0.RadioState.ON;
                break;

            default:
                throw new RuntimeException(
                        "Unrecognized RIL_RadioState: " + state);
        }

        radioIndication.radioStateChanged(indicationType, radioState);
    }

    private void handleRestrictedStateChanged(IRadioIndication radioIndication, int indicationType, Parcel p) throws RemoteException {

        int[] ints = readInts(p);

        if (ints.length > 0) {
            int state = ints[0];

            radioIndication.restrictedStateChanged(indicationType, state);
        }
    }

    private SignalStrength readSignalStrength(Parcel p){
        int gsmSignalStrength = p.readInt();
        int gsmBitErrorRate = p.readInt();
        int cdmaDbm = p.readInt();
        int cdmaEcio = p.readInt();
        int evdoDbm = p.readInt();
        int evdoEcio = p.readInt();
        int evdoSnr = p.readInt();
        int lteSignalStrength = p.readInt();
        int lteRsrp = p.readInt();
        int lteRsrq = p.readInt();
        int lteRssnr = p.readInt();
        int lteCqi = p.readInt();
        int tdScdmaRscp = p.readInt();

        SignalStrength signalStrength = new SignalStrength();

        // Fixup LTE for backwards compatibility
        // signalStrength: -1 -> 99
        if (lteSignalStrength == -1) {
            lteSignalStrength = 99;
        }
        // rsrp: -1 -> INT_MAX all other negative value to positive.
        // So remap here
        if (lteRsrp == -1) {
            lteRsrp = Integer.MAX_VALUE;
        } else if (lteRsrp < -1) {
            lteRsrp = -lteRsrp;
        }
        // rsrq: -1 -> INT_MAX
        if (lteRsrq == -1) {
            lteRsrq = Integer.MAX_VALUE;
        }
        // Not remapping rssnr is already using INT_MAX
        // cqi: -1 -> INT_MAX
        if (lteCqi == -1) {
            lteCqi = Integer.MAX_VALUE;
        }

        signalStrength.gw.signalStrength = gsmSignalStrength;
        signalStrength.gw.bitErrorRate = gsmBitErrorRate;
        signalStrength.cdma.dbm = cdmaDbm;
        signalStrength.cdma.ecio = cdmaEcio;
        signalStrength.evdo.dbm = evdoDbm;
        signalStrength.evdo.ecio = evdoEcio;
        signalStrength.evdo.signalNoiseRatio = evdoSnr;
        signalStrength.lte.signalStrength = lteSignalStrength;
        signalStrength.lte.rsrp = lteRsrp;
        signalStrength.lte.rsrq = lteRsrq;
        signalStrength.lte.rssnr = lteRssnr;
        signalStrength.lte.cqi = lteCqi;
        signalStrength.lte.timingAdvance = 0; //?? rilSignalStrength->LTE_SignalStrength.timingAdvance;
        signalStrength.tdScdma.rscp = tdScdmaRscp;

        return signalStrength;
    }

    private void handleSignalStrength(IRadioIndication radioIndication, int indicationType, Parcel p) throws RemoteException {

        SignalStrength signalStrength = readSignalStrength(p);

        radioIndication.currentSignalStrength(indicationType, signalStrength);
    }

    private void handeOemHookRaw(IOemHookIndication oemHookIndication, int indicationType, Parcel p) throws RemoteException {

        byte[] buffer = p.createByteArray();

        ArrayList<Byte> data = new ArrayList<Byte>();

        for (int i = 0; i < buffer.length; i++)
            data.add(buffer[i]);

        oemHookIndication.oemHookRaw(indicationType, data);
    }

    private int[] readInts(Parcel p) {
        int numInts;
        int response[];

        numInts = p.readInt();

        response = new int[numInts];

        for (int i = 0; i < numInts; i++) {
            response[i] = p.readInt();
        }

        return response;
    }

    private void handleCdmaPrlChanged(IRadioIndication radioIndication, int indicationType, Parcel p) throws RemoteException {

        int[] ents = readInts(p);

        int version = 0;

        if (ents.length > 0) {
            version = ents[0];

            radioIndication.cdmaPrlChanged(indicationType, ents[0]);
        }
    }

    private void handleCallRing(IRadioIndication radioIndication, int indicationType, Parcel p) throws RemoteException {

        boolean isGsm = true;
        CdmaSignalInfoRecord cdmaSignalInfoRecord = new CdmaSignalInfoRecord();

        if (p.dataAvail() > 0)
        {
            isGsm = false;

            cdmaSignalInfoRecord.isPresent = (p.readInt() != 0);
            cdmaSignalInfoRecord.signalType = (byte)p.readInt();
            cdmaSignalInfoRecord.alertPitch = (byte)p.readInt();
            cdmaSignalInfoRecord.signal = (byte)p.readInt();
        }

        radioIndication.callRing(indicationType, isGsm, cdmaSignalInfoRecord);
    }

    private void handleNitzTimeReceived(IRadioIndication radioIndication, int indicationType, Parcel p) throws RemoteException {

        String time = STRING_NULL_HANDLED(p.readString());
        long current = android.os.SystemClock.elapsedRealtime();

        radioIndication.nitzTimeReceived(indicationType, time, current);
    }


    private void handleNewSms(IRadioIndication radioIndication, int indicationType, Parcel p) throws RemoteException {

        ArrayList<Byte> data = new ArrayList<Byte>();

        String msg = STRING_NULL_HANDLED(p.readString());

        byte[] buffer = hexStringToBytes(msg); // msg.getBytes(Charset.forName("UTF-8"));

        for (int i = 0; i<buffer.length; i++)
            data.add(buffer[i]);


        radioIndication.newSms(indicationType, data);
    }

    private static int
    hexCharToInt(char c) {
        if (c >= '0' && c <= '9') return (c - '0');
        if (c >= 'A' && c <= 'F') return (c - 'A' + 10);
        if (c >= 'a' && c <= 'f') return (c - 'a' + 10);

        throw new RuntimeException ("invalid hex char '" + c + "'");
    }

    /**
     * Converts a hex String to a byte array.
     *
     * @param s A string of hexadecimal characters, must be an even number of
     *          chars long
     *
     * @return byte array representation
     *
     * @throws RuntimeException on invalid format
     */
    private static byte[]
    hexStringToBytes(String s) {
        byte[] ret;

        if (s == null) return null;

        int sz = s.length();

        ret = new byte[sz/2];

        for (int i=0 ; i <sz ; i+=2) {
            ret[i/2] = (byte) ((hexCharToInt(s.charAt(i)) << 4)
                    | hexCharToInt(s.charAt(i+1)));
        }

        return ret;
    }

    private void handleSmsStatusReport(IRadioIndication radioIndication, int indicationType, Parcel p) throws RemoteException {

        ArrayList<Byte> pdu = new ArrayList<Byte>();

        String msg = STRING_NULL_HANDLED(p.readString());

        byte[] buffer = hexStringToBytes(msg); // msg.getBytes(Charset.forName("UTF-8"));

        for (int i = 0; i<buffer.length; i++)
            pdu.add(buffer[i]);


        radioIndication.newSmsStatusReport(indicationType, pdu);
    }

    private void handleSmsOnSim(IRadioIndication radioIndication, int indicationType, Parcel p) throws RemoteException {

        int[] ints = readInts(p);

        if (ints != null && ints.length == 1) {

            int recordNumber = ints[0];

            radioIndication.newSmsOnSim(indicationType, recordNumber);
        }
        else
            Rlog.e(TAG, "handleSmsOnSim: recordNumber not found!");
    }

    private android.hardware.radio.V1_0.ActivityStatsInfo readActivityStatsInfo(Parcel p) {

        android.hardware.radio.V1_0.ActivityStatsInfo info = new android.hardware.radio.V1_0.ActivityStatsInfo();

        int numTxPowerLevels = info.txmModetimeMs.length;

        int sleepModeTimeMs = p.readInt();
        int idleModeTimeMs = p.readInt();
        int[] txModeTimeMs = new int[numTxPowerLevels];
        for (int i = 0; i < numTxPowerLevels; i++) {
            txModeTimeMs[i] = p.readInt();
        }
        int rxModeTimeMs = p.readInt();

        info.sleepModeTimeMs = sleepModeTimeMs;
        info.idleModeTimeMs = idleModeTimeMs;
        for (int i = 0; i < numTxPowerLevels; i++) {
            info.txmModetimeMs[i] = txModeTimeMs[i];
        }
        info.rxModeTimeMs = rxModeTimeMs;

        return info;
    }

    private boolean readNetworkSelectionMode(RadioResponseInfo responseInfo, Parcel p) {

        int[] response = readInts(p);

        if (response.length > 0) {
            return (response[0] == 1);
        } else {
            responseInfo.error = RILConstants.RIL_ERRNO_INVALID_RESPONSE;
            Rlog.w(TAG, "readNetworkSelectionMode: error : " + responseInfo.error);
            return false;
        }
    }

    private void handleGetDeviceIdentityResponse(IRadioResponse radioResponse, RadioResponseInfo responseInfo, Parcel p) throws RemoteException {
        String[] resp = p.readStringArray();

        if (resp == null && resp.length < 3)
            throw new RemoteException("invalid arguments");

        String imei = STRING_NULL_HANDLED(resp[0]);
        String imeisv = STRING_NULL_HANDLED(resp[1]);
        String esn = STRING_NULL_HANDLED(resp[2]);
        String meid = STRING_NULL_HANDLED(resp[3]);

        radioResponse.getDeviceIdentityResponse(responseInfo, imei, imeisv, esn, meid);
    }

    private int readFirstInt(RadioResponseInfo responseInfo, Parcel p) {
        int state = 0;

        if (responseInfo.error == RILConstants.SUCCESS) {
            int[] ints = readInts(p);

            if (ints.length > 0) {
                state = ints[0];
            } else {
                responseInfo.error = RILConstants.RIL_ERRNO_INVALID_RESPONSE;
                Rlog.w(TAG, "readFirstInt: error : " + responseInfo.error);
            }
        }

        return state;
    }

    private RadioCapability readRadioCapability(Parcel p) {

        int version = p.readInt();
        int session = p.readInt();
        int phase = p.readInt();
        int rat = p.readInt();
        String logicModemUuid = STRING_NULL_HANDLED(p.readString());
        int status = p.readInt();

        RadioCapability capability = new RadioCapability();

        capability.session = session;
        capability.phase = phase;
        capability.raf = rat;
        capability.logicalModemUuid = logicModemUuid;
        capability.status = status;

        return capability;
    }

    private LceStatusInfo readLceStatusInfo(Parcel p) {

        final int lceStatus = (int) p.readByte();
        final int actualInterval = p.readInt();

        LceStatusInfo statusInfo = new LceStatusInfo();

        statusInfo.lceStatus = lceStatus;
        statusInfo.actualIntervalMs = (byte) actualInterval;

        return statusInfo;
    }

    private void
    processUnsolicited(Parcel p, int type) {
        int response;
        Object ret;

        response = p.readInt();

//        // Follow new symantics of sending an Ack starting from RIL version 13
//        if (getRilVersion() >= 13 && type == RESPONSE_UNSOLICITED_ACK_EXP) {
//            Message msg;
//            RILRequest rr = RILRequest.obtain(RIL_RESPONSE_ACKNOWLEDGEMENT, null);
//            msg = mSender.obtainMessage(EVENT_SEND_ACK, rr);
//            acquireWakeLock(rr, FOR_ACK_WAKELOCK);
//            msg.sendToTarget();
//            if (RILJ_LOGD) {
//                riljLog("Unsol response received for " + responseToString(response) +
//                        " Sending ack to ril.cpp");
//            }
//        }

        if (type == RESPONSE_UNSOLICITED_ACK_EXP) {
            Rlog.w(TAG, "processUnsolicited: RESPONSE_UNSOLICITED_ACK_EXP not allowed");
        }

        IRadioIndication radioIndication = this.mRadioIndication;
        IOemHookIndication oemHookIndication = this.mOemHookIndication;

        if (response == RIL_UNSOL_OEM_HOOK_RAW) {
            if (oemHookIndication == null) {
                Rlog.e(TAG, "processUnsolicited: oemHookIndication == null for request: " + responseToString(response));
                return;
            }
        } else {
            if (radioIndication == null) {
                Rlog.e(TAG, "processUnsolicited: radioIndication == null for request: " + responseToString(response));
                return;
            }
        }


        int indicationType;

        if (type == RESPONSE_UNSOLICITED)
            indicationType = RadioIndicationType.UNSOLICITED;
        else
            indicationType = RadioIndicationType.UNSOLICITED_ACK_EXP;

        try {
            Rlog.d(TAG, "processUnsolicited: respons for " + responseToString(response));

            switch (response) {
/*
 cat libs/telephony/ril_unsol_commands.h \
 | egrep "^ *{RIL_" \
 | sed -re 's/\{([^,]+),[^,]+,([^}]+).+/case \1: \2(rr, p); break;/'
*/

                case RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED:
                    handleRadioStateChanged(radioIndication, indicationType, p);
                    break;
                case RIL_UNSOL_RESPONSE_CALL_STATE_CHANGED:
                    radioIndication.callStateChanged(indicationType);
                    break;
                case RIL_UNSOL_RESPONSE_NETWORK_STATE_CHANGED:
                    radioIndication.networkStateChanged(indicationType);
                    break;
                case RIL_UNSOL_RESPONSE_NEW_SMS: handleNewSms(radioIndication, indicationType, p); break;
                case RIL_UNSOL_RESPONSE_NEW_SMS_STATUS_REPORT: handleSmsStatusReport(radioIndication, indicationType, p); break;
                case RIL_UNSOL_RESPONSE_NEW_SMS_ON_SIM: handleSmsOnSim(radioIndication, indicationType, p); break;
//            case RIL_UNSOL_ON_USSD: ret =  responseStrings(p); break;
                case RIL_UNSOL_NITZ_TIME_RECEIVED: handleNitzTimeReceived(radioIndication, indicationType, p); break;
                case RIL_UNSOL_SIGNAL_STRENGTH:
                    handleSignalStrength(radioIndication, indicationType, p);
                    break;
//            case RIL_UNSOL_DATA_CALL_LIST_CHANGED: ret = responseDataCallList(p);break;
//            case RIL_UNSOL_SUPP_SVC_NOTIFICATION: ret = responseSuppServiceNotification(p); break;
                case RIL_UNSOL_STK_SESSION_END:
                    radioIndication.stkSessionEnd(indicationType);
                    break;
//            case RIL_UNSOL_STK_PROACTIVE_COMMAND: ret = responseString(p); break;
//            case RIL_UNSOL_STK_EVENT_NOTIFY: ret = responseString(p); break;
//            case RIL_UNSOL_STK_CALL_SETUP: ret = responseInts(p); break;
                case RIL_UNSOL_SIM_SMS_STORAGE_FULL:
                    radioIndication.simSmsStorageFull(indicationType);
                    break;
//            case RIL_UNSOL_SIM_REFRESH: ret =  responseSimRefresh(p); break;
                case RIL_UNSOL_CALL_RING: handleCallRing(radioIndication, indicationType, p); break;
                case RIL_UNSOL_RESTRICTED_STATE_CHANGED:
                    handleRestrictedStateChanged(radioIndication, indicationType, p);
                    break;
                case RIL_UNSOL_RESPONSE_SIM_STATUS_CHANGED:
                    radioIndication.simStatusChanged(indicationType);
                    break;
//            case RIL_UNSOL_RESPONSE_CDMA_NEW_SMS:  ret =  responseCdmaSms(p); break;
//            case RIL_UNSOL_RESPONSE_NEW_BROADCAST_SMS:  ret =  responseRaw(p); break;
                case RIL_UNSOL_CDMA_RUIM_SMS_STORAGE_FULL:
                    radioIndication.cdmaRuimSmsStorageFull(indicationType);
                    break;
                case RIL_UNSOL_ENTER_EMERGENCY_CALLBACK_MODE:
                    radioIndication.enterEmergencyCallbackMode(indicationType);
                    break;
//            case RIL_UNSOL_CDMA_CALL_WAITING: ret = responseCdmaCallWaiting(p); break;
//            case RIL_UNSOL_CDMA_OTA_PROVISION_STATUS: ret = responseInts(p); break;
//            case RIL_UNSOL_CDMA_INFO_REC: ret = responseCdmaInformationRecord(p); break;
                case RIL_UNSOL_OEM_HOOK_RAW:
                    handeOemHookRaw(oemHookIndication, indicationType, p);
                    break;
//            case RIL_UNSOL_RINGBACK_TONE: ret = responseInts(p); break;
                case RIL_UNSOL_RESEND_INCALL_MUTE:
                    radioIndication.resendIncallMute(indicationType);
                    break;
//            case RIL_UNSOL_CDMA_SUBSCRIPTION_SOURCE_CHANGED: ret = responseInts(p); break;
                case RIL_UNSOl_CDMA_PRL_CHANGED:
                    handleCdmaPrlChanged(radioIndication, indicationType, p);
                    break;
                case RIL_UNSOL_EXIT_EMERGENCY_CALLBACK_MODE:
                    radioIndication.exitEmergencyCallbackMode(indicationType);
                    break;
                case RIL_UNSOL_RIL_CONNECTED:
                    radioIndication.rilConnected(indicationType);
                    break;
//            case RIL_UNSOL_VOICE_RADIO_TECH_CHANGED: ret =  responseInts(p); break;
//            case RIL_UNSOL_CELL_INFO_LIST: ret = responseCellInfoList(p); break;
                case RIL_UNSOL_RESPONSE_IMS_NETWORK_STATE_CHANGED:
                    radioIndication.imsNetworkStateChanged(indicationType);
                    break;
//            case RIL_UNSOL_UICC_SUBSCRIPTION_STATUS_CHANGED: ret =  responseInts(p); break;
//            case RIL_UNSOL_SRVCC_STATE_NOTIFY: ret = responseInts(p); break;
//            case RIL_UNSOL_HARDWARE_CONFIG_CHANGED: ret = responseHardwareConfig(p); break;
//            case RIL_UNSOL_RADIO_CAPABILITY: ret = responseRadioCapability(p); break;
//            case RIL_UNSOL_ON_SS: ret =  responseSsData(p); break;
//            case RIL_UNSOL_STK_CC_ALPHA_NOTIFY: ret =  responseString(p); break;
//            case RIL_UNSOL_LCEDATA_RECV: ret = responseLceData(p); break;
//            case RIL_UNSOL_PCO_DATA: ret = responsePcoData(p); break;

                default:
                    throw new RuntimeException("Unrecognized unsol response: " + responseToString(response));
                    //break; (implied)
            }
        } catch (Throwable tr) {
            Rlog.e(TAG, "Exception processing unsol response: " + responseToString(response) +
                    "Exception:" + tr.toString());
            return;
        }

    }

    private CardStatus readCardStatus(Parcel p) {

        CardStatus cardStatus = new CardStatus();

        cardStatus.cardState = p.readInt();
        cardStatus.universalPinState = p.readInt();
        cardStatus.gsmUmtsSubscriptionAppIndex = p.readInt();
        cardStatus.cdmaSubscriptionAppIndex = p.readInt();
        cardStatus.imsSubscriptionAppIndex = p.readInt();

        int numApplications = p.readInt();

        for (int i = 0; i < numApplications; i++) {

            AppStatus appStatus = new AppStatus();


            appStatus.appType = p.readInt();
            appStatus.appState = p.readInt();
            appStatus.persoSubstate = p.readInt();
            appStatus.aidPtr = STRING_NULL_HANDLED(p.readString());
            appStatus.appLabelPtr = STRING_NULL_HANDLED(p.readString());
            appStatus.pin1Replaced = p.readInt();
            appStatus.pin1 = p.readInt();
            appStatus.pin2 = p.readInt();

            cardStatus.applications.add(appStatus);
        }
        return cardStatus;
    }

    private ArrayList<Call> readCalls(Parcel p) {
        int num;
        int voiceSettings;

        ArrayList<Call> response = new ArrayList<Call>();

        num = p.readInt();

        for (int i = 0; i < num; i++) {

            Call call = new Call();

            call.state = p.readInt();
            call.index = p.readInt();
            call.toa = p.readInt();
            call.isMpty = (0 != p.readInt());
            call.isMT = (0 != p.readInt());
            call.als = (byte) p.readInt();
            voiceSettings = p.readInt();
            call.isVoice = (0 == voiceSettings) ? false : true;
            call.isVoicePrivacy = (0 != p.readInt());
            call.number = STRING_NULL_HANDLED(p.readString());
            int np = p.readInt();
            call.numberPresentation = np;
            call.name = STRING_NULL_HANDLED(p.readString());
            // according to ril.h, namePresentation should be handled as numberPresentation;
            call.namePresentation = p.readInt();
            int uusInfoPresent = p.readInt();
            if (uusInfoPresent == 1) {

                UusInfo uusInfo = new UusInfo();


                uusInfo.uusType = p.readInt();
                uusInfo.uusDcs = p.readInt();
                byte[] userData = p.createByteArray();
                uusInfo.uusData = new String(userData, Charset.forName("UTF-8"));

                call.uusInfo.add(uusInfo);

            } else {
                Rlog.d(TAG, "Incoming UUS : NOT present!");
            }

            response.add(call);
        }

        return response;
    }

    private void handleGetImsRegistrationStateResponse(IRadioResponse radioResponse, RadioResponseInfo responseInfo, Parcel p) throws RemoteException {
        int[] ints = readInts(p);

        boolean isRegistered = false;
        int ratFamily = 0;

        if (responseInfo.error == RILConstants.SUCCESS) {
            if (ints.length > 1) {
                isRegistered = ints[0] == 1 ? true : false;
                ratFamily = ints[1];
            } else {
                responseInfo.error = RILConstants.RIL_ERRNO_INVALID_RESPONSE;
                Rlog.w(TAG, "handleGetImsRegistrationStateResponse: error : " + responseInfo.error);
            }
        }

        radioResponse.getImsRegistrationStateResponse(responseInfo, isRegistered, ratFamily);
    }

    private void handleIccOpenLogicalChannelResponse(IRadioResponse radioResponse, RadioResponseInfo responseInfo, Parcel p) throws RemoteException {
        int[] ints = readInts(p);

        int channelId = 0;
        ArrayList<Byte> selectResponse = new ArrayList<Byte>();

        if (responseInfo.error == RILConstants.SUCCESS) {
            if (ints.length > 0) {
                channelId = ints[0];

                for (int i = 1; i < ints.length; i++)
                    selectResponse.add((byte) ints[0]);
            } else {
                responseInfo.error = RILConstants.RIL_ERRNO_INVALID_RESPONSE;
                Rlog.w(TAG, "handleIccOpenLogicalChannelResponse: error : " + responseInfo.error);
            }
        }

        radioResponse.iccOpenLogicalChannelResponse(responseInfo, channelId, selectResponse);
    }

    private IccIoResult readIccIoResult(Parcel p) {

        IccIoResult iccIoResult = new IccIoResult();

        iccIoResult.sw1 = p.readInt();
        iccIoResult.sw2 = p.readInt();
        iccIoResult.simResponse = STRING_NULL_HANDLED(p.readString());

        return iccIoResult;
    }

    private SendSmsResult readSendSmsResult(Parcel p) {

        SendSmsResult sendSMSResult = new SendSmsResult();

        sendSMSResult.messageRef = p.readInt();
        sendSMSResult.ackPDU = STRING_NULL_HANDLED(p.readString());
        sendSMSResult.errorCode = p.readInt();

        return sendSMSResult;
    }

    private LastCallFailCauseInfo readLastCallFailCauseInfo(Parcel p) {

        LastCallFailCauseInfo lastCallFailCauseInfo = new LastCallFailCauseInfo();

        lastCallFailCauseInfo.causeCode = p.readInt();
        if (p.dataAvail() > 0) {
            lastCallFailCauseInfo.vendorCause = STRING_NULL_HANDLED(p.readString());
        }

        return lastCallFailCauseInfo;
    }

    // Type fields for parceling
    /**
     * @hide
     */
    protected static final int TYPE_GSM = 1;
    /**
     * @hide
     */
    protected static final int TYPE_CDMA = 2;
    /**
     * @hide
     */
    protected static final int TYPE_LTE = 3;
    /**
     * @hide
     */
    protected static final int TYPE_WCDMA = 4;

    private ArrayList<CellInfo> readCellInfo(Parcel p) {

        int numberOfInfoRecs;
        ArrayList<CellInfo> response;

        /**
         * Loop through all of the information records unmarshalling them
         * and converting them to Java Objects.
         */
        numberOfInfoRecs = p.readInt();
        response = new ArrayList<CellInfo>(numberOfInfoRecs);

        for (int i = 0; i < numberOfInfoRecs; i++) {
            CellInfo cellInfo = new CellInfo();

            cellInfo.cellInfoType = p.readInt();
            cellInfo.registered = (p.readInt() == 1) ? true : false;
            cellInfo.timeStampType = p.readInt();
            cellInfo.timeStamp = p.readLong();
            switch (cellInfo.cellInfoType) {

                case TYPE_GSM:
                    CellInfoGsm cellInfoGsm = new CellInfoGsm();
                    cellInfo.gsm.add(cellInfoGsm);

                    cellInfoGsm.cellIdentityGsm.mcc = Integer.toString(p.readInt());
                    cellInfoGsm.cellIdentityGsm.lac = p.readInt();
                    cellInfoGsm.cellIdentityGsm.cid = p.readInt();
                    cellInfoGsm.cellIdentityGsm.arfcn = p.readInt();
                    cellInfoGsm.cellIdentityGsm.bsic = (byte) p.readInt();

                    cellInfoGsm.signalStrengthGsm.signalStrength = p.readInt();
                    cellInfoGsm.signalStrengthGsm.bitErrorRate = p.readInt();
                    cellInfoGsm.signalStrengthGsm.timingAdvance = p.readInt();

                    break;

                case TYPE_CDMA:
                    CellInfoCdma cellInfoCdma = new CellInfoCdma();
                    cellInfo.cdma.add(cellInfoCdma);

                    cellInfoCdma.cellIdentityCdma.networkId = p.readInt();
                    cellInfoCdma.cellIdentityCdma.systemId = p.readInt();
                    cellInfoCdma.cellIdentityCdma.baseStationId = p.readInt();
                    cellInfoCdma.cellIdentityCdma.longitude = p.readInt();
                    cellInfoCdma.cellIdentityCdma.latitude = p.readInt();

                    cellInfoCdma.signalStrengthCdma.dbm = p.readInt();
                    cellInfoCdma.signalStrengthCdma.ecio = p.readInt();

                    cellInfoCdma.signalStrengthEvdo.dbm = p.readInt();
                    cellInfoCdma.signalStrengthEvdo.ecio = p.readInt();
                    cellInfoCdma.signalStrengthEvdo.signalNoiseRatio = p.readInt();

                    break;

                case TYPE_LTE:

                    CellInfoLte cellInfoLte = new CellInfoLte();
                    cellInfo.lte.add(cellInfoLte);

                    cellInfoLte.cellIdentityLte.mcc = Integer.toString(p.readInt());
                    cellInfoLte.cellIdentityLte.mnc = Integer.toString(p.readInt());
                    cellInfoLte.cellIdentityLte.ci = p.readInt();
                    cellInfoLte.cellIdentityLte.pci = p.readInt();
                    cellInfoLte.cellIdentityLte.tac = p.readInt();
                    cellInfoLte.cellIdentityLte.earfcn = p.readInt();

                    cellInfoLte.signalStrengthLte.signalStrength = p.readInt();
                    cellInfoLte.signalStrengthLte.rsrp = p.readInt();
                    cellInfoLte.signalStrengthLte.rsrq = p.readInt();
                    cellInfoLte.signalStrengthLte.rssnr = p.readInt();
                    cellInfoLte.signalStrengthLte.cqi = p.readInt();
                    cellInfoLte.signalStrengthLte.timingAdvance = p.readInt();

                    break;

                case TYPE_WCDMA:

                    CellInfoWcdma cellInfoWcdma = new CellInfoWcdma();
                    cellInfo.wcdma.add(cellInfoWcdma);

                    cellInfoWcdma.cellIdentityWcdma.mcc = Integer.toString(p.readInt());
                    cellInfoWcdma.cellIdentityWcdma.mnc = Integer.toString(p.readInt());
                    cellInfoWcdma.cellIdentityWcdma.lac = p.readInt();
                    cellInfoWcdma.cellIdentityWcdma.cid = p.readInt();
                    cellInfoWcdma.cellIdentityWcdma.psc = p.readInt();
                    cellInfoWcdma.cellIdentityWcdma.uarfcn = p.readInt();

                    cellInfoWcdma.signalStrengthWcdma.signalStrength = p.readInt();
                    cellInfoWcdma.signalStrengthWcdma.bitErrorRate = p.readInt();

                    break;
                default:
                    throw new RuntimeException("Bad CellInfo Parcel");
            }

            response.add(cellInfo);
        }
        return response;
    }

    private void
    processSolicited(Parcel p, int type) {
        int serial, error;
        boolean found = false;

        serial = p.readInt();
        error = p.readInt();

        if (type == RESPONSE_SOLICITED_ACK_EXP)
            Rlog.w(TAG, "processSolicited: RESPONSE_SOLICITED_ACK_EXP not allowed");
		
 /*       if (getRilVersion() >= 13 && type == RESPONSE_SOLICITED_ACK_EXP) {
            Message msg;
            RILRequest response = RILRequest.obtain(RIL_RESPONSE_ACKNOWLEDGEMENT, null);
            msg = mSender.obtainMessage(EVENT_SEND_ACK, response);
            acquireWakeLock(rr, FOR_ACK_WAKELOCK);
            msg.sendToTarget();
            if (RILJ_LOGD) {
                riljLog("Response received for " + rr.serialString() + " " +
                        requestToString(rr.mRequest) + " Sending ack to ril.cpp");
            }
        }
*/

        Integer code;

        synchronized (serialToRequestCode) {
            code = serialToRequestCode.remove(serial);
        }

        if (code == null)
        {
            Rlog.e(TAG, "processSolidcited: no request with given serial is pending: " + serial);
            return;
        }

        RadioResponseInfo responseInfo = new RadioResponseInfo();
        responseInfo.type = type;
        responseInfo.serial = serial;
        responseInfo.error = error;

        if (error != RILConstants.SUCCESS){
            Rlog.w(TAG, "processSolidcited: response with error for request " + requestToString(code) + ": " + error);
        }

        IRadioResponse radioResponse = this.mRadioResponse;
        IOemHookResponse oemHookResponse = this.mOemHookResponse;

        if (code == RIL_REQUEST_OEM_HOOK_RAW) {
            if (oemHookResponse == null) {
                Rlog.e(TAG, "processSolidcited: oemHookResponse == null for request: " + requestToString(code));
                return;
            }
        }
        else {
            if (radioResponse == null) {
                Rlog.e(TAG, "processSolidcited: radioResponse == null for request: " + requestToString(code));
                return;
            }
        }

        try {
            Rlog.d(TAG, "processSolicited: respons for " + requestToString(code));

            switch (code) {
                case RIL_REQUEST_GET_SIM_STATUS: radioResponse.getIccCardStatusResponse(responseInfo, readCardStatus(p)); break;
//                case RIL_REQUEST_ENTER_SIM_PIN: ret =  responseInts(p); break;
//                case RIL_REQUEST_ENTER_SIM_PUK: ret =  responseInts(p); break;
//                case RIL_REQUEST_ENTER_SIM_PIN2: ret =  responseInts(p); break;
//                case RIL_REQUEST_ENTER_SIM_PUK2: ret =  responseInts(p); break;
//                case RIL_REQUEST_CHANGE_SIM_PIN: ret =  responseInts(p); break;
//                case RIL_REQUEST_CHANGE_SIM_PIN2: ret =  responseInts(p); break;
//                case RIL_REQUEST_ENTER_NETWORK_DEPERSONALIZATION: ret =  responseInts(p); break;
                case RIL_REQUEST_GET_CURRENT_CALLS: radioResponse.getCurrentCallsResponse(responseInfo, readCalls(p)); break;
                case RIL_REQUEST_DIAL: radioResponse.dialResponse(responseInfo); break;
                case RIL_REQUEST_GET_IMSI: radioResponse.getIMSIForAppResponse(responseInfo, STRING_NULL_HANDLED(p.readString())); break;
                case RIL_REQUEST_HANGUP: radioResponse.hangupConnectionResponse(responseInfo); break;
                case RIL_REQUEST_HANGUP_WAITING_OR_BACKGROUND: radioResponse.hangupWaitingOrBackgroundResponse(responseInfo); break;
                case RIL_REQUEST_HANGUP_FOREGROUND_RESUME_BACKGROUND: radioResponse.hangupForegroundResumeBackgroundResponse(responseInfo); break;
                case RIL_REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE: radioResponse.switchWaitingOrHoldingAndActiveResponse(responseInfo); break;
                case RIL_REQUEST_CONFERENCE: radioResponse.conferenceResponse(responseInfo); break;
                case RIL_REQUEST_UDUB: radioResponse.rejectCallResponse(responseInfo); break;
                case RIL_REQUEST_LAST_CALL_FAIL_CAUSE: radioResponse.getLastCallFailCauseResponse(responseInfo, readLastCallFailCauseInfo(p)); break;
                case RIL_REQUEST_SIGNAL_STRENGTH: radioResponse.getSignalStrengthResponse(responseInfo, readSignalStrength(p)); break;
                case RIL_REQUEST_VOICE_REGISTRATION_STATE: radioResponse.getVoiceRegistrationStateResponse(responseInfo, readVoiceRegStateResult(p)); break;
                case RIL_REQUEST_DATA_REGISTRATION_STATE: radioResponse.getDataRegistrationStateResponse(responseInfo, readDataRegStateResult(p)); break;
                case RIL_REQUEST_OPERATOR: handleGetOperatorResponse(radioResponse, responseInfo, p); break;
                case RIL_REQUEST_RADIO_POWER: radioResponse.setRadioPowerResponse(responseInfo); break;
                case RIL_REQUEST_DTMF: radioResponse.sendDtmfResponse(responseInfo); break;
                case RIL_REQUEST_SEND_SMS: radioResponse.sendSmsResponse(responseInfo, readSendSmsResult(p)); break;
                case RIL_REQUEST_SEND_SMS_EXPECT_MORE: radioResponse.sendSMSExpectMoreResponse(responseInfo,  readSendSmsResult(p)); break;
////                case RIL_REQUEST_SETUP_DATA_CALL: ret =  responseSetupDataCall(p); break;
               case RIL_REQUEST_SIM_IO: radioResponse.iccIOForAppResponse(responseInfo, readIccIoResult(p)); break;
//                case RIL_REQUEST_SEND_USSD: ret =  responseVoid(p); break;
//                case RIL_REQUEST_CANCEL_USSD: ret =  responseVoid(p); break;
////                case RIL_REQUEST_GET_CLIR: ret =  responseInts(p); break;
//                case RIL_REQUEST_SET_CLIR: ret =  responseVoid(p); break;
////                case RIL_REQUEST_QUERY_CALL_FORWARD_STATUS: ret =  responseCallForward(p); break;
//                case RIL_REQUEST_SET_CALL_FORWARD: ret =  responseVoid(p); break;
////                case RIL_REQUEST_QUERY_CALL_WAITING: ret =  responseInts(p); break;
//                case RIL_REQUEST_SET_CALL_WAITING: ret =  responseVoid(p); break;
                case RIL_REQUEST_SMS_ACKNOWLEDGE: radioResponse.acknowledgeLastIncomingGsmSmsResponse(responseInfo); break;
////                case RIL_REQUEST_GET_IMEI: ret =  responseString(p); break;
////                case RIL_REQUEST_GET_IMEISV: ret =  responseString(p); break;
                case RIL_REQUEST_ANSWER: radioResponse.acceptCallResponse(responseInfo); break;
//                case RIL_REQUEST_DEACTIVATE_DATA_CALL: ret =  responseVoid(p); break;
                case RIL_REQUEST_QUERY_FACILITY_LOCK: radioResponse.getFacilityLockForAppResponse(responseInfo, readFirstInt(responseInfo, p)); break;
////                case RIL_REQUEST_SET_FACILITY_LOCK: ret =  responseInts(p); break;
//                case RIL_REQUEST_CHANGE_BARRING_PASSWORD: ret =  responseVoid(p); break;
                case RIL_REQUEST_QUERY_NETWORK_SELECTION_MODE: radioResponse.getNetworkSelectionModeResponse(responseInfo, readNetworkSelectionMode(responseInfo, p)); break;
//                case RIL_REQUEST_SET_NETWORK_SELECTION_AUTOMATIC: ret =  responseVoid(p); break;
//                case RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL: ret =  responseVoid(p); break;
////                case RIL_REQUEST_QUERY_AVAILABLE_NETWORKS : ret =  responseOperatorInfos(p); break;
                case RIL_REQUEST_DTMF_START: radioResponse.sendDtmfResponse(responseInfo); break;
                case RIL_REQUEST_DTMF_STOP: radioResponse.sendDtmfResponse(responseInfo); break;
                case RIL_REQUEST_BASEBAND_VERSION: radioResponse.getBasebandVersionResponse(responseInfo, readBaseBandVersion(p)); break;
//                case RIL_REQUEST_SEPARATE_CONNECTION: ret =  responseVoid(p); break;
                case RIL_REQUEST_SET_MUTE: radioResponse.setMuteResponse(responseInfo); break;
////                case RIL_REQUEST_GET_MUTE: ret =  responseInts(p); break;
////                case RIL_REQUEST_QUERY_CLIP: ret =  responseInts(p); break;
////                case RIL_REQUEST_LAST_DATA_CALL_FAIL_CAUSE: ret =  responseInts(p); break;
////                case RIL_REQUEST_DATA_CALL_LIST: ret =  responseDataCallList(p); break;
//                case RIL_REQUEST_RESET_RADIO: ret =  responseVoid(p); break;
                case RIL_REQUEST_OEM_HOOK_RAW: oemHookResponse.sendRequestRawResponse(responseInfo, readOemRaw(p)); break;
                case RIL_REQUEST_OEM_HOOK_STRINGS: oemHookResponse.sendRequestStringsResponse(responseInfo, readOemStrings(p)); break;
//                case RIL_REQUEST_SCREEN_STATE: ret =  responseVoid(p); break;
                case RIL_REQUEST_SET_SUPP_SVC_NOTIFICATION: radioResponse.setSuppServiceNotificationsResponse(responseInfo); break;
////                case RIL_REQUEST_WRITE_SMS_TO_SIM: ret =  responseInts(p); break;
//                case RIL_REQUEST_DELETE_SMS_ON_SIM: ret =  responseVoid(p); break;
//                case RIL_REQUEST_SET_BAND_MODE: ret =  responseVoid(p); break;
//                case RIL_REQUEST_QUERY_AVAILABLE_BAND_MODE: ret =  responseInts(p); break;
//                case RIL_REQUEST_STK_GET_PROFILE: ret =  responseString(p); break;
//                case RIL_REQUEST_STK_SET_PROFILE: ret =  responseVoid(p); break;
//                case RIL_REQUEST_STK_SEND_ENVELOPE_COMMAND: ret =  responseString(p); break;
//                case RIL_REQUEST_STK_SEND_TERMINAL_RESPONSE: ret =  responseVoid(p); break;
//                case RIL_REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM: ret =  responseInts(p); break;
//                case RIL_REQUEST_EXPLICIT_CALL_TRANSFER: ret =  responseVoid(p); break;
//                case RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE: ret =  responseVoid(p); break;
//                case RIL_REQUEST_GET_PREFERRED_NETWORK_TYPE: ret =  responseGetPreferredNetworkType(p); break;
//                case RIL_REQUEST_GET_NEIGHBORING_CELL_IDS: ret = responseCellList(p); break;
//                case RIL_REQUEST_SET_LOCATION_UPDATES: ret =  responseVoid(p); break;
               case RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE: radioResponse.setCdmaSubscriptionSourceResponse(responseInfo); break;
//                case RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE: ret =  responseVoid(p); break;
//                case RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE: ret =  responseInts(p); break;
//                case RIL_REQUEST_SET_TTY_MODE: ret =  responseVoid(p); break;
//                case RIL_REQUEST_QUERY_TTY_MODE: ret =  responseInts(p); break;
//                case RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE: ret =  responseVoid(p); break;
//                case RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE: ret =  responseInts(p); break;
//                case RIL_REQUEST_CDMA_FLASH: ret =  responseVoid(p); break;
//                case RIL_REQUEST_CDMA_BURST_DTMF: ret =  responseVoid(p); break;
//                case RIL_REQUEST_CDMA_SEND_SMS: ret =  responseSMS(p); break;
//                case RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE: ret =  responseVoid(p); break;
//                case RIL_REQUEST_GSM_GET_BROADCAST_CONFIG: ret =  responseGmsBroadcastConfig(p); break;
                case RIL_REQUEST_GSM_SET_BROADCAST_CONFIG: radioResponse.setGsmBroadcastConfigResponse(responseInfo); break;
                case RIL_REQUEST_GSM_BROADCAST_ACTIVATION: radioResponse.setGsmBroadcastActivationResponse(responseInfo); break;
//                case RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG: ret =  responseCdmaBroadcastConfig(p); break;
                case RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG: radioResponse.setCdmaBroadcastConfigResponse(responseInfo); break;
                case RIL_REQUEST_CDMA_BROADCAST_ACTIVATION: radioResponse.setCdmaBroadcastActivationResponse(responseInfo); break;
//                case RIL_REQUEST_CDMA_VALIDATE_AND_WRITE_AKEY: ret =  responseVoid(p); break;
//                case RIL_REQUEST_CDMA_SUBSCRIPTION: ret =  responseStrings(p); break;
//                case RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM: ret =  responseInts(p); break;
//                case RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM: ret =  responseVoid(p); break;
                case RIL_REQUEST_DEVICE_IDENTITY: handleGetDeviceIdentityResponse(radioResponse, responseInfo, p); break;
//                case RIL_REQUEST_GET_SMSC_ADDRESS: ret = responseString(p); break;
//                case RIL_REQUEST_SET_SMSC_ADDRESS: ret = responseVoid(p); break;
//                case RIL_REQUEST_EXIT_EMERGENCY_CALLBACK_MODE: ret = responseVoid(p); break;
//                case RIL_REQUEST_REPORT_SMS_MEMORY_STATUS: ret = responseVoid(p); break;
                case RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING: radioResponse.reportStkServiceIsRunningResponse(responseInfo); break;
                case RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE: radioResponse.getCdmaSubscriptionSourceResponse(responseInfo, readFirstInt(responseInfo, p)); break;
//                case RIL_REQUEST_ISIM_AUTHENTICATION: ret =  responseString(p); break;
                case RIL_REQUEST_ACKNOWLEDGE_INCOMING_GSM_SMS_WITH_PDU: radioResponse.acknowledgeIncomingGsmSmsWithPduResponse(responseInfo); break;
//                case RIL_REQUEST_STK_SEND_ENVELOPE_WITH_STATUS: ret = responseICC_IO(p); break;
                case RIL_REQUEST_VOICE_RADIO_TECH: radioResponse.getVoiceRadioTechnologyResponse(responseInfo, readFirstInt(responseInfo, p)); break;
                case RIL_REQUEST_GET_CELL_INFO_LIST: radioResponse.getCellInfoListResponse(responseInfo, readCellInfo(p)); break;
                case RIL_REQUEST_SET_UNSOL_CELL_INFO_LIST_RATE: radioResponse.setCellInfoListRateResponse(responseInfo); break;
//                case RIL_REQUEST_SET_INITIAL_ATTACH_APN: ret = responseVoid(p); break;
//                case RIL_REQUEST_SET_DATA_PROFILE: ret = responseVoid(p); break;
                case RIL_REQUEST_IMS_REGISTRATION_STATE: handleGetImsRegistrationStateResponse(radioResponse, responseInfo, p); break;
//                case RIL_REQUEST_IMS_SEND_SMS: ret =  responseSMS(p); break;
//                case RIL_REQUEST_SIM_TRANSMIT_APDU_BASIC: ret =  responseICC_IO(p); break;
                case RIL_REQUEST_SIM_OPEN_CHANNEL: handleIccOpenLogicalChannelResponse(radioResponse, responseInfo, p); break;
//                case RIL_REQUEST_SIM_CLOSE_CHANNEL: ret  = responseVoid(p); break;
//                case RIL_REQUEST_SIM_TRANSMIT_APDU_CHANNEL: ret = responseICC_IO(p); break;
//                case RIL_REQUEST_NV_READ_ITEM: ret = responseString(p); break;
//                case RIL_REQUEST_NV_WRITE_ITEM: ret = responseVoid(p); break;
//                case RIL_REQUEST_NV_WRITE_CDMA_PRL: ret = responseVoid(p); break;
//                case RIL_REQUEST_NV_RESET_CONFIG: ret = responseVoid(p); break;
//                case RIL_REQUEST_SET_UICC_SUBSCRIPTION: ret = responseVoid(p); break;
                case RIL_REQUEST_ALLOW_DATA: radioResponse.setDataAllowedResponse(responseInfo); break;
//                case RIL_REQUEST_GET_HARDWARE_CONFIG: ret = responseHardwareConfig(p); break;
//                case RIL_REQUEST_SIM_AUTHENTICATION: ret =  responseICC_IOBase64(p); break;
//                case RIL_REQUEST_SHUTDOWN: ret = responseVoid(p); break;
                case RIL_REQUEST_GET_RADIO_CAPABILITY: radioResponse.getRadioCapabilityResponse(responseInfo, readRadioCapability(p)); break;
//                case RIL_REQUEST_SET_RADIO_CAPABILITY: ret =  responseRadioCapability(p); break;
                case RIL_REQUEST_START_LCE: radioResponse.startLceServiceResponse(responseInfo, readLceStatusInfo(p)); break;
                case RIL_REQUEST_STOP_LCE: radioResponse.stopLceServiceResponse(responseInfo, readLceStatusInfo(p)); break;
//                case RIL_REQUEST_PULL_LCEDATA: ret = responseLceData(p); break;
                case RIL_REQUEST_GET_ACTIVITY_INFO: radioResponse.getModemActivityInfoResponse(responseInfo, readActivityStatsInfo(p)); break;
//                case RIL_REQUEST_SET_ALLOWED_CARRIERS: ret = responseInts(p); break;
//                case RIL_REQUEST_GET_ALLOWED_CARRIERS: ret = responseCarrierIdentifiers(p); break;

                default:
                    Rlog.w(TAG, "processSolicited: unkown request code: " + requestToString(code) + ", for serial: " + serial);
                    break;
            }
        } catch (RuntimeException ex) //android.os.RemoteException ex)
        {
            Rlog.e(TAG, "processSolicited: error: " + ex);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    static String
    responseToString(int request)
    {
/*
 cat libs/telephony/ril_unsol_commands.h \
 | egrep "^ *{RIL_" \
 | sed -re 's/\{RIL_([^,]+),[^,]+,([^}]+).+/case RIL_\1: return "\1";/'
*/
        switch(request) {
            case RIL_UNSOL_RESPONSE_RADIO_STATE_CHANGED: return "UNSOL_RESPONSE_RADIO_STATE_CHANGED";
            case RIL_UNSOL_RESPONSE_CALL_STATE_CHANGED: return "UNSOL_RESPONSE_CALL_STATE_CHANGED";
            case RIL_UNSOL_RESPONSE_NETWORK_STATE_CHANGED: return "UNSOL_RESPONSE_NETWORK_STATE_CHANGED";
            case RIL_UNSOL_RESPONSE_NEW_SMS: return "UNSOL_RESPONSE_NEW_SMS";
            case RIL_UNSOL_RESPONSE_NEW_SMS_STATUS_REPORT: return "UNSOL_RESPONSE_NEW_SMS_STATUS_REPORT";
            case RIL_UNSOL_RESPONSE_NEW_SMS_ON_SIM: return "UNSOL_RESPONSE_NEW_SMS_ON_SIM";
            case RIL_UNSOL_ON_USSD: return "UNSOL_ON_USSD";
            case RIL_UNSOL_ON_USSD_REQUEST: return "UNSOL_ON_USSD_REQUEST";
            case RIL_UNSOL_NITZ_TIME_RECEIVED: return "UNSOL_NITZ_TIME_RECEIVED";
            case RIL_UNSOL_SIGNAL_STRENGTH: return "UNSOL_SIGNAL_STRENGTH";
            case RIL_UNSOL_DATA_CALL_LIST_CHANGED: return "UNSOL_DATA_CALL_LIST_CHANGED";
            case RIL_UNSOL_SUPP_SVC_NOTIFICATION: return "UNSOL_SUPP_SVC_NOTIFICATION";
            case RIL_UNSOL_STK_SESSION_END: return "UNSOL_STK_SESSION_END";
            case RIL_UNSOL_STK_PROACTIVE_COMMAND: return "UNSOL_STK_PROACTIVE_COMMAND";
            case RIL_UNSOL_STK_EVENT_NOTIFY: return "UNSOL_STK_EVENT_NOTIFY";
            case RIL_UNSOL_STK_CALL_SETUP: return "UNSOL_STK_CALL_SETUP";
            case RIL_UNSOL_SIM_SMS_STORAGE_FULL: return "UNSOL_SIM_SMS_STORAGE_FULL";
            case RIL_UNSOL_SIM_REFRESH: return "UNSOL_SIM_REFRESH";
            case RIL_UNSOL_CALL_RING: return "UNSOL_CALL_RING";
            case RIL_UNSOL_RESPONSE_SIM_STATUS_CHANGED: return "UNSOL_RESPONSE_SIM_STATUS_CHANGED";
            case RIL_UNSOL_RESPONSE_CDMA_NEW_SMS: return "UNSOL_RESPONSE_CDMA_NEW_SMS";
            case RIL_UNSOL_RESPONSE_NEW_BROADCAST_SMS: return "UNSOL_RESPONSE_NEW_BROADCAST_SMS";
            case RIL_UNSOL_CDMA_RUIM_SMS_STORAGE_FULL: return "UNSOL_CDMA_RUIM_SMS_STORAGE_FULL";
            case RIL_UNSOL_RESTRICTED_STATE_CHANGED: return "UNSOL_RESTRICTED_STATE_CHANGED";
            case RIL_UNSOL_ENTER_EMERGENCY_CALLBACK_MODE: return "UNSOL_ENTER_EMERGENCY_CALLBACK_MODE";
            case RIL_UNSOL_CDMA_CALL_WAITING: return "UNSOL_CDMA_CALL_WAITING";
            case RIL_UNSOL_CDMA_OTA_PROVISION_STATUS: return "UNSOL_CDMA_OTA_PROVISION_STATUS";
            case RIL_UNSOL_CDMA_INFO_REC: return "UNSOL_CDMA_INFO_REC";
            case RIL_UNSOL_OEM_HOOK_RAW: return "UNSOL_OEM_HOOK_RAW";
            case RIL_UNSOL_RINGBACK_TONE: return "UNSOL_RINGBACK_TONE";
            case RIL_UNSOL_RESEND_INCALL_MUTE: return "UNSOL_RESEND_INCALL_MUTE";
            case RIL_UNSOL_CDMA_SUBSCRIPTION_SOURCE_CHANGED: return "CDMA_SUBSCRIPTION_SOURCE_CHANGED";
            case RIL_UNSOl_CDMA_PRL_CHANGED: return "UNSOL_CDMA_PRL_CHANGED";
            case RIL_UNSOL_EXIT_EMERGENCY_CALLBACK_MODE: return "UNSOL_EXIT_EMERGENCY_CALLBACK_MODE";
            case RIL_UNSOL_RIL_CONNECTED: return "UNSOL_RIL_CONNECTED";
            case RIL_UNSOL_VOICE_RADIO_TECH_CHANGED: return "UNSOL_VOICE_RADIO_TECH_CHANGED";
            case RIL_UNSOL_CELL_INFO_LIST: return "UNSOL_CELL_INFO_LIST";
            case RIL_UNSOL_RESPONSE_IMS_NETWORK_STATE_CHANGED:
                return "UNSOL_RESPONSE_IMS_NETWORK_STATE_CHANGED";
            case RIL_UNSOL_UICC_SUBSCRIPTION_STATUS_CHANGED:
                return "RIL_UNSOL_UICC_SUBSCRIPTION_STATUS_CHANGED";
            case RIL_UNSOL_SRVCC_STATE_NOTIFY:
                return "UNSOL_SRVCC_STATE_NOTIFY";
            case RIL_UNSOL_HARDWARE_CONFIG_CHANGED: return "RIL_UNSOL_HARDWARE_CONFIG_CHANGED";
            case RIL_UNSOL_RADIO_CAPABILITY:
                return "RIL_UNSOL_RADIO_CAPABILITY";
            case RIL_UNSOL_ON_SS: return "UNSOL_ON_SS";
            case RIL_UNSOL_STK_CC_ALPHA_NOTIFY: return "UNSOL_STK_CC_ALPHA_NOTIFY";
            case RIL_UNSOL_LCEDATA_RECV: return "UNSOL_LCE_INFO_RECV";
            case RIL_UNSOL_PCO_DATA: return "UNSOL_PCO_DATA";
            default: return "<unknown response>";
        }
    }

    static String
    requestToString(int request) {
/*
 cat libs/telephony/ril_commands.h \
 | egrep "^ *{RIL_" \
 | sed -re 's/\{RIL_([^,]+),[^,]+,([^}]+).+/case RIL_\1: return "\1";/'
*/
        switch (request) {
            case RIL_REQUEST_GET_SIM_STATUS:
                return "GET_SIM_STATUS";
            case RIL_REQUEST_ENTER_SIM_PIN:
                return "ENTER_SIM_PIN";
            case RIL_REQUEST_ENTER_SIM_PUK:
                return "ENTER_SIM_PUK";
            case RIL_REQUEST_ENTER_SIM_PIN2:
                return "ENTER_SIM_PIN2";
            case RIL_REQUEST_ENTER_SIM_PUK2:
                return "ENTER_SIM_PUK2";
            case RIL_REQUEST_CHANGE_SIM_PIN:
                return "CHANGE_SIM_PIN";
            case RIL_REQUEST_CHANGE_SIM_PIN2:
                return "CHANGE_SIM_PIN2";
            case RIL_REQUEST_ENTER_NETWORK_DEPERSONALIZATION:
                return "ENTER_NETWORK_DEPERSONALIZATION";
            case RIL_REQUEST_GET_CURRENT_CALLS:
                return "GET_CURRENT_CALLS";
            case RIL_REQUEST_DIAL:
                return "DIAL";
            case RIL_REQUEST_GET_IMSI:
                return "GET_IMSI";
            case RIL_REQUEST_HANGUP:
                return "HANGUP";
            case RIL_REQUEST_HANGUP_WAITING_OR_BACKGROUND:
                return "HANGUP_WAITING_OR_BACKGROUND";
            case RIL_REQUEST_HANGUP_FOREGROUND_RESUME_BACKGROUND:
                return "HANGUP_FOREGROUND_RESUME_BACKGROUND";
            case RIL_REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE:
                return "REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE";
            case RIL_REQUEST_CONFERENCE:
                return "CONFERENCE";
            case RIL_REQUEST_UDUB:
                return "UDUB";
            case RIL_REQUEST_LAST_CALL_FAIL_CAUSE:
                return "LAST_CALL_FAIL_CAUSE";
            case RIL_REQUEST_SIGNAL_STRENGTH:
                return "SIGNAL_STRENGTH";
            case RIL_REQUEST_VOICE_REGISTRATION_STATE:
                return "VOICE_REGISTRATION_STATE";
            case RIL_REQUEST_DATA_REGISTRATION_STATE:
                return "DATA_REGISTRATION_STATE";
            case RIL_REQUEST_OPERATOR:
                return "OPERATOR";
            case RIL_REQUEST_RADIO_POWER:
                return "RADIO_POWER";
            case RIL_REQUEST_DTMF:
                return "DTMF";
            case RIL_REQUEST_SEND_SMS:
                return "SEND_SMS";
            case RIL_REQUEST_SEND_SMS_EXPECT_MORE:
                return "SEND_SMS_EXPECT_MORE";
            case RIL_REQUEST_SETUP_DATA_CALL:
                return "SETUP_DATA_CALL";
            case RIL_REQUEST_SIM_IO:
                return "SIM_IO";
            case RIL_REQUEST_SEND_USSD:
                return "SEND_USSD";
            case RIL_REQUEST_CANCEL_USSD:
                return "CANCEL_USSD";
            case RIL_REQUEST_GET_CLIR:
                return "GET_CLIR";
            case RIL_REQUEST_SET_CLIR:
                return "SET_CLIR";
            case RIL_REQUEST_QUERY_CALL_FORWARD_STATUS:
                return "QUERY_CALL_FORWARD_STATUS";
            case RIL_REQUEST_SET_CALL_FORWARD:
                return "SET_CALL_FORWARD";
            case RIL_REQUEST_QUERY_CALL_WAITING:
                return "QUERY_CALL_WAITING";
            case RIL_REQUEST_SET_CALL_WAITING:
                return "SET_CALL_WAITING";
            case RIL_REQUEST_SMS_ACKNOWLEDGE:
                return "SMS_ACKNOWLEDGE";
            case RIL_REQUEST_GET_IMEI:
                return "GET_IMEI";
            case RIL_REQUEST_GET_IMEISV:
                return "GET_IMEISV";
            case RIL_REQUEST_ANSWER:
                return "ANSWER";
            case RIL_REQUEST_DEACTIVATE_DATA_CALL:
                return "DEACTIVATE_DATA_CALL";
            case RIL_REQUEST_QUERY_FACILITY_LOCK:
                return "QUERY_FACILITY_LOCK";
            case RIL_REQUEST_SET_FACILITY_LOCK:
                return "SET_FACILITY_LOCK";
            case RIL_REQUEST_CHANGE_BARRING_PASSWORD:
                return "CHANGE_BARRING_PASSWORD";
            case RIL_REQUEST_QUERY_NETWORK_SELECTION_MODE:
                return "QUERY_NETWORK_SELECTION_MODE";
            case RIL_REQUEST_SET_NETWORK_SELECTION_AUTOMATIC:
                return "SET_NETWORK_SELECTION_AUTOMATIC";
            case RIL_REQUEST_SET_NETWORK_SELECTION_MANUAL:
                return "SET_NETWORK_SELECTION_MANUAL";
            case RIL_REQUEST_QUERY_AVAILABLE_NETWORKS:
                return "QUERY_AVAILABLE_NETWORKS ";
            case RIL_REQUEST_DTMF_START:
                return "DTMF_START";
            case RIL_REQUEST_DTMF_STOP:
                return "DTMF_STOP";
            case RIL_REQUEST_BASEBAND_VERSION:
                return "BASEBAND_VERSION";
            case RIL_REQUEST_SEPARATE_CONNECTION:
                return "SEPARATE_CONNECTION";
            case RIL_REQUEST_SET_MUTE:
                return "SET_MUTE";
            case RIL_REQUEST_GET_MUTE:
                return "GET_MUTE";
            case RIL_REQUEST_QUERY_CLIP:
                return "QUERY_CLIP";
            case RIL_REQUEST_LAST_DATA_CALL_FAIL_CAUSE:
                return "LAST_DATA_CALL_FAIL_CAUSE";
            case RIL_REQUEST_DATA_CALL_LIST:
                return "DATA_CALL_LIST";
            case RIL_REQUEST_RESET_RADIO:
                return "RESET_RADIO";
            case RIL_REQUEST_OEM_HOOK_RAW:
                return "OEM_HOOK_RAW";
            case RIL_REQUEST_OEM_HOOK_STRINGS:
                return "OEM_HOOK_STRINGS";
            case RIL_REQUEST_SCREEN_STATE:
                return "SCREEN_STATE";
            case RIL_REQUEST_SET_SUPP_SVC_NOTIFICATION:
                return "SET_SUPP_SVC_NOTIFICATION";
            case RIL_REQUEST_WRITE_SMS_TO_SIM:
                return "WRITE_SMS_TO_SIM";
            case RIL_REQUEST_DELETE_SMS_ON_SIM:
                return "DELETE_SMS_ON_SIM";
            case RIL_REQUEST_SET_BAND_MODE:
                return "SET_BAND_MODE";
            case RIL_REQUEST_QUERY_AVAILABLE_BAND_MODE:
                return "QUERY_AVAILABLE_BAND_MODE";
            case RIL_REQUEST_STK_GET_PROFILE:
                return "REQUEST_STK_GET_PROFILE";
            case RIL_REQUEST_STK_SET_PROFILE:
                return "REQUEST_STK_SET_PROFILE";
            case RIL_REQUEST_STK_SEND_ENVELOPE_COMMAND:
                return "REQUEST_STK_SEND_ENVELOPE_COMMAND";
            case RIL_REQUEST_STK_SEND_TERMINAL_RESPONSE:
                return "REQUEST_STK_SEND_TERMINAL_RESPONSE";
            case RIL_REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM:
                return "REQUEST_STK_HANDLE_CALL_SETUP_REQUESTED_FROM_SIM";
            case RIL_REQUEST_EXPLICIT_CALL_TRANSFER:
                return "REQUEST_EXPLICIT_CALL_TRANSFER";
            case RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE:
                return "REQUEST_SET_PREFERRED_NETWORK_TYPE";
            case RIL_REQUEST_GET_PREFERRED_NETWORK_TYPE:
                return "REQUEST_GET_PREFERRED_NETWORK_TYPE";
            case RIL_REQUEST_GET_NEIGHBORING_CELL_IDS:
                return "REQUEST_GET_NEIGHBORING_CELL_IDS";
            case RIL_REQUEST_SET_LOCATION_UPDATES:
                return "REQUEST_SET_LOCATION_UPDATES";
            case RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE:
                return "RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE";
            case RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE:
                return "RIL_REQUEST_CDMA_SET_ROAMING_PREFERENCE";
            case RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE:
                return "RIL_REQUEST_CDMA_QUERY_ROAMING_PREFERENCE";
            case RIL_REQUEST_SET_TTY_MODE:
                return "RIL_REQUEST_SET_TTY_MODE";
            case RIL_REQUEST_QUERY_TTY_MODE:
                return "RIL_REQUEST_QUERY_TTY_MODE";
            case RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE:
                return "RIL_REQUEST_CDMA_SET_PREFERRED_VOICE_PRIVACY_MODE";
            case RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE:
                return "RIL_REQUEST_CDMA_QUERY_PREFERRED_VOICE_PRIVACY_MODE";
            case RIL_REQUEST_CDMA_FLASH:
                return "RIL_REQUEST_CDMA_FLASH";
            case RIL_REQUEST_CDMA_BURST_DTMF:
                return "RIL_REQUEST_CDMA_BURST_DTMF";
            case RIL_REQUEST_CDMA_SEND_SMS:
                return "RIL_REQUEST_CDMA_SEND_SMS";
            case RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE:
                return "RIL_REQUEST_CDMA_SMS_ACKNOWLEDGE";
            case RIL_REQUEST_GSM_GET_BROADCAST_CONFIG:
                return "RIL_REQUEST_GSM_GET_BROADCAST_CONFIG";
            case RIL_REQUEST_GSM_SET_BROADCAST_CONFIG:
                return "RIL_REQUEST_GSM_SET_BROADCAST_CONFIG";
            case RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG:
                return "RIL_REQUEST_CDMA_GET_BROADCAST_CONFIG";
            case RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG:
                return "RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG";
            case RIL_REQUEST_GSM_BROADCAST_ACTIVATION:
                return "RIL_REQUEST_GSM_BROADCAST_ACTIVATION";
            case RIL_REQUEST_CDMA_VALIDATE_AND_WRITE_AKEY:
                return "RIL_REQUEST_CDMA_VALIDATE_AND_WRITE_AKEY";
            case RIL_REQUEST_CDMA_BROADCAST_ACTIVATION:
                return "RIL_REQUEST_CDMA_BROADCAST_ACTIVATION";
            case RIL_REQUEST_CDMA_SUBSCRIPTION:
                return "RIL_REQUEST_CDMA_SUBSCRIPTION";
            case RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM:
                return "RIL_REQUEST_CDMA_WRITE_SMS_TO_RUIM";
            case RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM:
                return "RIL_REQUEST_CDMA_DELETE_SMS_ON_RUIM";
            case RIL_REQUEST_DEVICE_IDENTITY:
                return "RIL_REQUEST_DEVICE_IDENTITY";
            case RIL_REQUEST_GET_SMSC_ADDRESS:
                return "RIL_REQUEST_GET_SMSC_ADDRESS";
            case RIL_REQUEST_SET_SMSC_ADDRESS:
                return "RIL_REQUEST_SET_SMSC_ADDRESS";
            case RIL_REQUEST_EXIT_EMERGENCY_CALLBACK_MODE:
                return "REQUEST_EXIT_EMERGENCY_CALLBACK_MODE";
            case RIL_REQUEST_REPORT_SMS_MEMORY_STATUS:
                return "RIL_REQUEST_REPORT_SMS_MEMORY_STATUS";
            case RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING:
                return "RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING";
            case RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE:
                return "RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE";
            case RIL_REQUEST_ISIM_AUTHENTICATION:
                return "RIL_REQUEST_ISIM_AUTHENTICATION";
            case RIL_REQUEST_ACKNOWLEDGE_INCOMING_GSM_SMS_WITH_PDU:
                return "RIL_REQUEST_ACKNOWLEDGE_INCOMING_GSM_SMS_WITH_PDU";
            case RIL_REQUEST_STK_SEND_ENVELOPE_WITH_STATUS:
                return "RIL_REQUEST_STK_SEND_ENVELOPE_WITH_STATUS";
            case RIL_REQUEST_VOICE_RADIO_TECH:
                return "RIL_REQUEST_VOICE_RADIO_TECH";
            case RIL_REQUEST_GET_CELL_INFO_LIST:
                return "RIL_REQUEST_GET_CELL_INFO_LIST";
            case RIL_REQUEST_SET_UNSOL_CELL_INFO_LIST_RATE:
                return "RIL_REQUEST_SET_CELL_INFO_LIST_RATE";
            case RIL_REQUEST_SET_INITIAL_ATTACH_APN:
                return "RIL_REQUEST_SET_INITIAL_ATTACH_APN";
            case RIL_REQUEST_SET_DATA_PROFILE:
                return "RIL_REQUEST_SET_DATA_PROFILE";
            case RIL_REQUEST_IMS_REGISTRATION_STATE:
                return "RIL_REQUEST_IMS_REGISTRATION_STATE";
            case RIL_REQUEST_IMS_SEND_SMS:
                return "RIL_REQUEST_IMS_SEND_SMS";
            case RIL_REQUEST_SIM_TRANSMIT_APDU_BASIC:
                return "RIL_REQUEST_SIM_TRANSMIT_APDU_BASIC";
            case RIL_REQUEST_SIM_OPEN_CHANNEL:
                return "RIL_REQUEST_SIM_OPEN_CHANNEL";
            case RIL_REQUEST_SIM_CLOSE_CHANNEL:
                return "RIL_REQUEST_SIM_CLOSE_CHANNEL";
            case RIL_REQUEST_SIM_TRANSMIT_APDU_CHANNEL:
                return "RIL_REQUEST_SIM_TRANSMIT_APDU_CHANNEL";
            case RIL_REQUEST_NV_READ_ITEM:
                return "RIL_REQUEST_NV_READ_ITEM";
            case RIL_REQUEST_NV_WRITE_ITEM:
                return "RIL_REQUEST_NV_WRITE_ITEM";
            case RIL_REQUEST_NV_WRITE_CDMA_PRL:
                return "RIL_REQUEST_NV_WRITE_CDMA_PRL";
            case RIL_REQUEST_NV_RESET_CONFIG:
                return "RIL_REQUEST_NV_RESET_CONFIG";
            case RIL_REQUEST_SET_UICC_SUBSCRIPTION:
                return "RIL_REQUEST_SET_UICC_SUBSCRIPTION";
            case RIL_REQUEST_ALLOW_DATA:
                return "RIL_REQUEST_ALLOW_DATA";
            case RIL_REQUEST_GET_HARDWARE_CONFIG:
                return "GET_HARDWARE_CONFIG";
            case RIL_REQUEST_SIM_AUTHENTICATION:
                return "RIL_REQUEST_SIM_AUTHENTICATION";
            case RIL_REQUEST_SHUTDOWN:
                return "RIL_REQUEST_SHUTDOWN";
            case RIL_REQUEST_SET_RADIO_CAPABILITY:
                return "RIL_REQUEST_SET_RADIO_CAPABILITY";
            case RIL_REQUEST_GET_RADIO_CAPABILITY:
                return "RIL_REQUEST_GET_RADIO_CAPABILITY";
            case RIL_REQUEST_START_LCE:
                return "RIL_REQUEST_START_LCE";
            case RIL_REQUEST_STOP_LCE:
                return "RIL_REQUEST_STOP_LCE";
            case RIL_REQUEST_PULL_LCEDATA:
                return "RIL_REQUEST_PULL_LCEDATA";
            case RIL_REQUEST_GET_ACTIVITY_INFO:
                return "RIL_REQUEST_GET_ACTIVITY_INFO";
            case RIL_REQUEST_SET_ALLOWED_CARRIERS:
                return "RIL_REQUEST_SET_ALLOWED_CARRIERS";
            case RIL_REQUEST_GET_ALLOWED_CARRIERS:
                return "RIL_REQUEST_GET_ALLOWED_CARRIERS";
            case RIL_RESPONSE_ACKNOWLEDGEMENT:
                return "RIL_RESPONSE_ACKNOWLEDGEMENT";
            default:
                return "<unknown request>";
        }
    }

    private Parcel getRequest(int code, int serial){
        Parcel p = Parcel.obtain();

        p.writeInt(code);
        p.writeInt(serial);

        return p;
    }

    private void
    send(int serial, int code, Parcel p)
            throws android.os.RemoteException {
        Rlog.d(TAG, "send invoked!");

        LocalSocket s = mSocket;

        if (s == null) {
            // rr.onError(RADIO_NOT_AVAILABLE, null);
            // rr.release();
            Rlog.e(TAG, "send: mSocket == null!");
            return;
        }

        synchronized (serialToRequestCode) {
            serialToRequestCode.put(serial, code);
        }

        byte[] data;

        data = p.marshall();
        p.recycle();

        if (data.length > RIL_MAX_COMMAND_BYTES) {
            Rlog.e(TAG, "send: data.length > RIL_MAX_COMMAND_BYTES");
            throw new RuntimeException(
                    "Parcel larger than max bytes allowed! "
                            + data.length);
        }

        // parcel length in big endian

        //RRlog.v(RILJ_LOG_TAG, "writing packet: " + data.length + " bytes");

        try {
            synchronized (sendSyncObj) {

                dataLength[0] = dataLength[1] = 0;
                dataLength[2] = (byte) ((data.length >> 8) & 0xff);
                dataLength[3] = (byte) ((data.length) & 0xff);

                s.getOutputStream().write(dataLength);
                s.getOutputStream().write(data);
            }
        } catch (IOException ex) {
            Rlog.e(TAG, "send: ex: " + ex);
            throw new android.os.RemoteException(ex.getMessage());
        }
    }

    private void tryConnectRil(){

        if (mRadioResponse != null && mOemHookResponse != null) {
            if (mReceiver == null) {
                mReceiver = new RILReceiver();
                mReceiverThread = new Thread(mReceiver, "RILReceiver" + mInstanceId);
                mReceiverThread.start();
            }
        }
    }

    class RadioImpl extends IRadio.Stub {
        private static final boolean DEBUG = true;
        private static final String TAG = "RildService.RadioImpl";

        @Override
        public void setResponseFunctions(IRadioResponse radioResponse, IRadioIndication radioIndication)
                throws android.os.RemoteException {

            Rlog.d(TAG, "setResponseFunctions called!");

            mRadioResponse = radioResponse;
            mRadioIndication = radioIndication;

            tryConnectRil();
        }

        @Override
        public void getIccCardStatus(int serial)
                throws android.os.RemoteException {

            Rlog.d(TAG, "getIccCardStatus called!");

            int code = RIL_REQUEST_GET_SIM_STATUS;

            Parcel p = getRequest(code, serial);
            send(serial, code, p);
        }

        @Override
        public void supplyIccPinForApp(int serial, String pin, String aid)
                throws android.os.RemoteException {

            Rlog.e(TAG, "supplyIccPinForApp called!");
        }

        @Override
        public void supplyIccPukForApp(int serial, String puk, String pin, String aid)
                throws android.os.RemoteException {

            Rlog.e(TAG, "supplyIccPukForApp called!");
        }

        @Override
        public void supplyIccPin2ForApp(int serial, String pin2, String aid)
                throws android.os.RemoteException {

            Rlog.e(TAG, "supplyIccPin2ForApp called!");
        }

        @Override
        public void supplyIccPuk2ForApp(int serial, String puk2, String pin2, String aid)
                throws android.os.RemoteException {

            Rlog.e(TAG, "supplyIccPuk2ForApp called!");
        }

        @Override
        public void changeIccPinForApp(int serial, String oldPin, String newPin, String aid)
                throws android.os.RemoteException {

            Rlog.e(TAG, "changeIccPinForApp called!");
        }

        @Override
        public void changeIccPin2ForApp(int serial, String oldPin2, String newPin2, String aid)
                throws android.os.RemoteException {

            Rlog.e(TAG, "changeIccPin2ForApp called!");
        }

        @Override
        public void supplyNetworkDepersonalization(int serial, String netPin)
                throws android.os.RemoteException {

            Rlog.e(TAG, "supplyNetworkDepersonalization called!");
        }

        @Override
        public void getCurrentCalls(int serial)
                throws android.os.RemoteException {

            Rlog.d(TAG, "getCurrentCalls called!");

            int code = RIL_REQUEST_GET_CURRENT_CALLS;

            Parcel p = getRequest(code, serial);
            send(serial, code, p);
        }

        @Override
        public void dial(int serial, Dial dialInfo)
                throws android.os.RemoteException {

            Rlog.d(TAG, "dial called!");

            int code = RIL_REQUEST_DIAL;

            Parcel p = getRequest(code, serial);

            p.writeString(STRING_TORIL_NULL_HANDLED(dialInfo.address));
            p.writeInt(dialInfo.clir);

            if (dialInfo.uusInfo == null || dialInfo.uusInfo.size() == 0) {
                p.writeInt(0); // UUS information is absent
            } else {
                p.writeInt(1); // UUS information is present
                p.writeInt(dialInfo.uusInfo.get(0).uusType);
                p.writeInt(dialInfo.uusInfo.get(0).uusDcs);
                p.writeByteArray(dialInfo.uusInfo.get(0).uusData.getBytes(Charset.forName("UTF-8")));
            }

            send(serial, code, p);
        }

        @Override
        public void getImsiForApp(int serial, String aid)
                throws android.os.RemoteException {

            Rlog.d(TAG, "getImsiForApp called!");

            int code = RIL_REQUEST_GET_IMSI;

            Parcel p = getRequest(code, serial);

            p.writeString(STRING_TORIL_NULL_HANDLED(aid));

            send(serial, code, p);
        }

        @Override
        public void hangup(int serial, int gsmIndex)
                throws android.os.RemoteException {

            Rlog.d(TAG, "hangup called!");

            int code = RIL_REQUEST_HANGUP;

            Parcel p = getRequest(code, serial);

            p.writeInt(1);
            p.writeInt(gsmIndex);

            send(serial, code, p);
        }

        @Override
        public void hangupWaitingOrBackground(int serial)
                throws android.os.RemoteException {

            Rlog.d(TAG, "hangupWaitingOrBackground called!");

            int code = RIL_REQUEST_HANGUP_WAITING_OR_BACKGROUND;

            Parcel p = getRequest(code, serial);

            send(serial, code, p);
        }

        @Override
        public void hangupForegroundResumeBackground(int serial)
                throws android.os.RemoteException {

            Rlog.d(TAG, "hangupForegroundResumeBackground called!");


            int code = RIL_REQUEST_HANGUP_FOREGROUND_RESUME_BACKGROUND;

            Parcel p = getRequest(code, serial);

            send(serial, code, p);
        }

        @Override
        public void switchWaitingOrHoldingAndActive(int serial)
                throws android.os.RemoteException {

            Rlog.d(TAG, "switchWaitingOrHoldingAndActive called!");

            int code = RIL_REQUEST_SWITCH_WAITING_OR_HOLDING_AND_ACTIVE;

            Parcel p = getRequest(code, serial);

            send(serial, code, p);
        }

        @Override
        public void conference(int serial)
                throws android.os.RemoteException {

            Rlog.d(TAG, "conference called!");

            int code = RIL_REQUEST_CONFERENCE;

            Parcel p = getRequest(code, serial);

            send(serial, code, p);
        }

        @Override
        public void rejectCall(int serial)
                throws android.os.RemoteException {

            Rlog.d(TAG, "rejectCall called!");

            int code = RIL_REQUEST_UDUB;

            Parcel p = getRequest(code, serial);

            send(serial, code, p);
        }

        @Override
        public void getLastCallFailCause(int serial)
                throws android.os.RemoteException {

            Rlog.d(TAG, "getLastCallFailCause called!");

            int code = RIL_REQUEST_LAST_CALL_FAIL_CAUSE;

            Parcel p = getRequest(code, serial);

            send(serial, code, p);
        }

        @Override
        public void getSignalStrength(int serial)
                throws android.os.RemoteException {

            Rlog.d(TAG, "getSignalStrength called!");

            int code = RIL_REQUEST_SIGNAL_STRENGTH;

            Parcel p = getRequest(code, serial);

            send(serial, code, p);
        }

        @Override
        public void getVoiceRegistrationState(int serial)
                throws android.os.RemoteException {

            Rlog.d(TAG, "getVoiceRegistrationState called!");

            int code = RIL_REQUEST_VOICE_REGISTRATION_STATE;
            Parcel p = getRequest(code, serial);
            send(serial, code, p);
        }

        @Override
        public void getDataRegistrationState(int serial)
                throws android.os.RemoteException {

            Rlog.d(TAG, "getDataRegistrationState called!");

            int code = RIL_REQUEST_DATA_REGISTRATION_STATE;

            Parcel p = getRequest(code, serial);
            send(serial, code, p);
         }

        @Override
        public void getOperator(int serial)
                throws android.os.RemoteException {

            Rlog.d(TAG, "getOperator called!");

            int code = RIL_REQUEST_OPERATOR;

            Parcel p = getRequest(code, serial);
            send(serial, code, p);
        }

        @Override
        public void setRadioPower(int serial, boolean on)
                throws android.os.RemoteException {

            Rlog.d(TAG, "setRadioPower called!");

            int code = RIL_REQUEST_RADIO_POWER;

            Parcel p = getRequest(code, serial);

            p.writeInt(1);
            p.writeInt(on ? 1 : 0);

            send(serial, code, p);
        }

        @Override
        public void sendDtmf(int serial, String s)
                throws android.os.RemoteException {

            Rlog.d(TAG, "sendDtmf called!");

            int code = RIL_REQUEST_DTMF;

            Parcel p = getRequest(code, serial);

            p.writeString(s);

            send(serial, code, p);
        }

        @Override
        public void sendSms(int serial, GsmSmsMessage message)
                throws android.os.RemoteException {

            Rlog.d(TAG, "sendSms called!");

            int code = RIL_REQUEST_SEND_SMS;

            Parcel p = getRequest(code, serial);

            p.writeInt(2);
            p.writeString(STRING_TORIL_NULL_HANDLED(message.smscPdu));
            p.writeString(STRING_TORIL_NULL_HANDLED(message.pdu));

            send(serial, code, p);
        }

        @Override
        public void sendSMSExpectMore(int serial, GsmSmsMessage message)
                throws android.os.RemoteException {

            Rlog.d(TAG, "sendSMSExpectMore called!");

            int code = RIL_REQUEST_SEND_SMS_EXPECT_MORE;

            Parcel p = getRequest(code, serial);

            p.writeInt(2);
            p.writeString(STRING_TORIL_NULL_HANDLED(message.smscPdu));
            p.writeString(STRING_TORIL_NULL_HANDLED(message.pdu));

            send(serial, code, p);

        }

        @Override
        public void setupDataCall(int serial, int radioTechnology, DataProfileInfo dataProfileInfo, boolean modemCognitive, boolean roamingAllowed, boolean isRoaming)
                throws android.os.RemoteException {

            Rlog.e(TAG, "setupDataCall called!");
        }

        @Override
        public void iccIOForApp(int serial, IccIo iccIo)
                throws android.os.RemoteException {

            Rlog.d(TAG, "iccIOForApp called!");

            int code = RIL_REQUEST_SIM_IO;

            Parcel p = getRequest(code, serial);

            p.writeInt(iccIo.command);
            p.writeInt(iccIo.fileId);
            p.writeString(STRING_TORIL_NULL_HANDLED(iccIo.path));
            p.writeInt(iccIo.p1);
            p.writeInt(iccIo.p2);
            p.writeInt(iccIo.p3);
            p.writeString(STRING_TORIL_NULL_HANDLED(iccIo.data));
            p.writeString(STRING_TORIL_NULL_HANDLED(iccIo.pin2));
            p.writeString(STRING_TORIL_NULL_HANDLED(iccIo.aid));

            send(serial, code, p);
        }

        @Override
        public void sendUssd(int serial, String ussd)
                throws android.os.RemoteException {

            Rlog.e(TAG, "sendUssd called!");
        }

        @Override
        public void cancelPendingUssd(int serial)
                throws android.os.RemoteException {

            Rlog.e(TAG, "cancelPendingUssd called!");
        }

        @Override
        public void getClir(int serial)
                throws android.os.RemoteException {

            Rlog.e(TAG, "getClir called!");
        }

        @Override
        public void setClir(int serial, int status)
                throws android.os.RemoteException {

            Rlog.e(TAG, "setClir called!");
        }

        @Override
        public void getCallForwardStatus(int serial, CallForwardInfo callInfo)
                throws android.os.RemoteException {

            Rlog.e(TAG, "getCallForwardStatus called!");
        }

        @Override
        public void setCallForward(int serial, CallForwardInfo callInfo)
                throws android.os.RemoteException {

            Rlog.e(TAG, "setCallForward called!");
        }

        @Override
        public void getCallWaiting(int serial, int serviceClass)
                throws android.os.RemoteException {

            Rlog.e(TAG, "getCallWaiting called!");
        }

        @Override
        public void setCallWaiting(int serial, boolean enable, int serviceClass)
                throws android.os.RemoteException {

            Rlog.e(TAG, "setCallWaiting called!");
        }

        @Override
        public void acknowledgeLastIncomingGsmSms(int serial, boolean success, int cause)
                throws android.os.RemoteException {

            Rlog.d(TAG, "acknowledgeLastIncomingGsmSms called!");

            int code = RIL_REQUEST_SMS_ACKNOWLEDGE;

            Parcel p = getRequest(code, serial);

            p.writeInt(2);
            p.writeInt(success ? 1 : 0);
            p.writeInt(cause);

            send(serial, code, p);
        }

        @Override
        public void acceptCall(int serial)
                throws android.os.RemoteException {

            Rlog.d(TAG, "acceptCall called!");

            int code = RIL_REQUEST_ANSWER;

            Parcel p = getRequest(code, serial);

            send(serial, code, p);
        }

        @Override
        public void deactivateDataCall(int serial, int cid, boolean reasonRadioShutDown)
                throws android.os.RemoteException {

            Rlog.e(TAG, "deactivateDataCall called!");
        }

        @Override
        public void getFacilityLockForApp(int serial, String facility, String password, int serviceClass, String appId)
                throws android.os.RemoteException {

            Rlog.d(TAG, "getFacilityLockForApp called!");

            int code = RIL_REQUEST_QUERY_FACILITY_LOCK;

            Parcel p = getRequest(code, serial);

            p.writeInt(4);

            p.writeString(STRING_TORIL_NULL_HANDLED(facility));
            p.writeString(STRING_TORIL_NULL_HANDLED(password));

            p.writeString(Integer.toString(serviceClass));
            p.writeString(STRING_TORIL_NULL_HANDLED(appId));

            send(serial, code, p);
        }

        @Override
        public void setFacilityLockForApp(int serial, String facility, boolean lockState, String password, int serviceClass, String appId)
                throws android.os.RemoteException {

            Rlog.e(TAG, "setFacilityLockForApp called!");
        }

        @Override
        public void setBarringPassword(int serial, String facility, String oldPassword, String newPassword)
                throws android.os.RemoteException {

            Rlog.e(TAG, "setBarringPassword called!");
        }

        @Override
        public void getNetworkSelectionMode(int serial)
                throws android.os.RemoteException {

            Rlog.d(TAG, "getNetworkSelectionMode called!");

            int code = RIL_REQUEST_QUERY_NETWORK_SELECTION_MODE;

            Parcel p = getRequest(code, serial);

            send(serial, code, p);
        }

        @Override
        public void setNetworkSelectionModeAutomatic(int serial)
                throws android.os.RemoteException {

            Rlog.e(TAG, "setNetworkSelectionModeAutomatic called!");
        }

        @Override
        public void setNetworkSelectionModeManual(int serial, String operatorNumeric)
                throws android.os.RemoteException {

            Rlog.e(TAG, "setNetworkSelectionModeManual called!");
        }

        @Override
        public void getAvailableNetworks(int serial)
                throws android.os.RemoteException {

            Rlog.e(TAG, "getAvailableNetworks called!");
        }

        @Override
        public void startDtmf(int serial, String s)
                throws android.os.RemoteException {

            Rlog.d(TAG, "startDtmf called!");

            int code = RIL_REQUEST_DTMF_START;

            Parcel p = getRequest(code, serial);

            p.writeString(s);

            send(serial, code, p);
        }

        @Override
        public void stopDtmf(int serial)
                throws android.os.RemoteException {

            Rlog.d(TAG, "stopDtmf called!");

            int code = RIL_REQUEST_DTMF_STOP;

            Parcel p = getRequest(code, serial);

            send(serial, code, p);
        }

        @Override
        public void getBasebandVersion(int serial)
                throws android.os.RemoteException {

            Rlog.d(TAG, "getBasebandVersion called!");

            int code = RIL_REQUEST_BASEBAND_VERSION;

            Parcel p = getRequest(code, serial);

            send(serial, code, p);
        }

        @Override
        public void separateConnection(int serial, int gsmIndex)
                throws android.os.RemoteException {

            Rlog.e(TAG, "separateConnection called!");
        }

        @Override
        public void setMute(int serial, boolean enable)
                throws android.os.RemoteException {

            Rlog.d(TAG, "setMute called!");

            int code = RIL_REQUEST_SET_MUTE;

            Parcel p = getRequest(code, serial);

            p.writeInt(1);
            p.writeInt(enable ? 1 : 0);

            send(serial, code, p);
        }

        @Override
        public void getMute(int serial)
                throws android.os.RemoteException {

            Rlog.e(TAG, "getMute called!");
        }

        @Override
        public void getClip(int serial)
                throws android.os.RemoteException {

            Rlog.e(TAG, "getClip called!");
        }

        @Override
        public void getDataCallList(int serial)
                throws android.os.RemoteException {

            Rlog.e(TAG, "getDataCallList called!");
        }

        @Override
        public void setSuppServiceNotifications(int serial, boolean enable)
                throws android.os.RemoteException {

            Rlog.d(TAG, "setSuppServiceNotifications called!");

            int code = RIL_REQUEST_SET_SUPP_SVC_NOTIFICATION;

            Parcel p = getRequest(code, serial);

            p.writeInt(1);
            p.writeInt(enable ? 1 : 0);

            send(serial, code, p);
        }

        @Override
        public void writeSmsToSim(int serial, SmsWriteArgs smsWriteArgs)
                throws android.os.RemoteException {

            Rlog.e(TAG, "writeSmsToSim called!");
        }

        @Override
        public void deleteSmsOnSim(int serial, int index)
                throws android.os.RemoteException {

            Rlog.e(TAG, "deleteSmsOnSim called!");
        }

        @Override
        public void setBandMode(int serial, int mode)
                throws android.os.RemoteException {

            Rlog.e(TAG, "setBandMode called!");
        }

        @Override
        public void getAvailableBandModes(int serial)
                throws android.os.RemoteException {

            Rlog.e(TAG, "getAvailableBandModes called!");
        }

        @Override
        public void sendEnvelope(int serial, String command)
                throws android.os.RemoteException {

            Rlog.e(TAG, "sendEnvelope called!");
        }

        @Override
        public void sendTerminalResponseToSim(int serial, String commandResponse)
                throws android.os.RemoteException {

            Rlog.e(TAG, "sendTerminalResponseToSim called!");
        }

        @Override
        public void handleStkCallSetupRequestFromSim(int serial, boolean accept)
                throws android.os.RemoteException {

            Rlog.e(TAG, "handleStkCallSetupRequestFromSim called!");
        }

        @Override
        public void explicitCallTransfer(int serial)
                throws android.os.RemoteException {

            Rlog.e(TAG, "explicitCallTransfer called!");
        }

        @Override
        public void setPreferredNetworkType(int serial, int nwType)
                throws android.os.RemoteException {

            Rlog.e(TAG, "setPreferredNetworkType called!");
        }

        @Override
        public void getPreferredNetworkType(int serial)
                throws android.os.RemoteException {

            Rlog.e(TAG, "getPreferredNetworkType called!");
        }

        @Override
        public void getNeighboringCids(int serial)
                throws android.os.RemoteException {

            Rlog.e(TAG, "getNeighboringCids called!");
        }

        @Override
        public void setLocationUpdates(int serial, boolean enable)
                throws android.os.RemoteException {

            Rlog.e(TAG, "setLocationUpdates called!");
        }

        @Override
        public void setCdmaSubscriptionSource(int serial, int cdmaSub)
                throws android.os.RemoteException {

            Rlog.d(TAG, "setCdmaSubscriptionSource called!");

            int code = RIL_REQUEST_CDMA_SET_SUBSCRIPTION_SOURCE;

            Parcel p = getRequest(code, serial);

            p.writeInt(1);
            p.writeInt(cdmaSub);

            send(serial, code, p);
        }

        @Override
        public void setCdmaRoamingPreference(int serial, int type)
                throws android.os.RemoteException {

            Rlog.e(TAG, "setCdmaRoamingPreference called!");
        }

        @Override
        public void getCdmaRoamingPreference(int serial)
                throws android.os.RemoteException {

            Rlog.e(TAG, "getCdmaRoamingPreference called!");
        }

        @Override
        public void setTTYMode(int serial, int mode)
                throws android.os.RemoteException {

            Rlog.e(TAG, "setTTYMode called!");
        }

        @Override
        public void getTTYMode(int serial)
                throws android.os.RemoteException {

            Rlog.e(TAG, "getTTYMode called!");
        }

        @Override
        public void setPreferredVoicePrivacy(int serial, boolean enable)
                throws android.os.RemoteException {

            Rlog.e(TAG, "setPreferredVoicePrivacy called!");
        }

        @Override
        public void getPreferredVoicePrivacy(int serial)
                throws android.os.RemoteException {

            Rlog.e(TAG, "getPreferredVoicePrivacy called!");
        }

        @Override
        public void sendCDMAFeatureCode(int serial, String featureCode)
                throws android.os.RemoteException {

            Rlog.e(TAG, "sendCDMAFeatureCode called!");
        }

        @Override
        public void sendBurstDtmf(int serial, String dtmf, int on, int off)
                throws android.os.RemoteException {

            Rlog.e(TAG, "sendBurstDtmf called!");
        }

        @Override
        public void sendCdmaSms(int serial, CdmaSmsMessage sms)
                throws android.os.RemoteException {

            Rlog.e(TAG, "sendCdmaSms called!");
        }

        @Override
        public void acknowledgeLastIncomingCdmaSms(int serial, CdmaSmsAck smsAck)
                throws android.os.RemoteException {

            Rlog.e(TAG, "acknowledgeLastIncomingCdmaSms called!");
        }

        @Override
        public void getGsmBroadcastConfig(int serial)
                throws android.os.RemoteException {

            Rlog.e(TAG, "getGsmBroadcastConfig called!");
        }

        @Override
        public void setGsmBroadcastConfig(int serial, java.util.ArrayList<GsmBroadcastSmsConfigInfo> configInfo)
                throws android.os.RemoteException {

            Rlog.d(TAG, "setGsmBroadcastConfig called!");

            int code = RIL_REQUEST_GSM_SET_BROADCAST_CONFIG;

            Parcel p = getRequest(code, serial);

            p.writeInt(configInfo.size());
            for(int i = 0; i < configInfo.size(); i++) {
                p.writeInt(configInfo.get(i).fromServiceId);
                p.writeInt(configInfo.get(i).toServiceId);
                p.writeInt(configInfo.get(i).fromCodeScheme);
                p.writeInt(configInfo.get(i).toCodeScheme);
                p.writeInt(configInfo.get(i).selected ? 1 : 0);
            }

            send(serial, code, p);
        }

        @Override
        public void setGsmBroadcastActivation(int serial, boolean activate)
                throws android.os.RemoteException {

            Rlog.d(TAG, "setGsmBroadcastActivation called!");

            int code = RIL_REQUEST_GSM_BROADCAST_ACTIVATION;

            Parcel p = getRequest(code, serial);

            p.writeInt(1);
            p.writeInt(activate ? 0 : 1);

            send(serial, code, p);
        }

        @Override
        public void getCdmaBroadcastConfig(int serial)
                throws android.os.RemoteException {

            Rlog.e(TAG, "getCdmaBroadcastConfig called!");
        }

        @Override
        public void setCdmaBroadcastConfig(int serial, java.util.ArrayList<CdmaBroadcastSmsConfigInfo> configInfo)
                throws android.os.RemoteException {

            Rlog.d(TAG, "setCdmaBroadcastConfig called!");

            int code = RIL_REQUEST_CDMA_SET_BROADCAST_CONFIG;

            Parcel p = getRequest(code, serial);

            p.writeInt(configInfo.size());
            for(int i = 0; i < configInfo.size(); i++) {
                p.writeInt(configInfo.get(i).serviceCategory);
                p.writeInt(configInfo.get(i).language);
                p.writeInt(configInfo.get(i).selected ? 1 : 0);
            }

            send(serial, code, p);
        }

        @Override
        public void setCdmaBroadcastActivation(int serial, boolean activate)
                throws android.os.RemoteException {

            Rlog.d(TAG, "setCdmaBroadcastActivation called!");

            int code = RIL_REQUEST_CDMA_BROADCAST_ACTIVATION;

            Parcel p = getRequest(code, serial);

            p.writeInt(1);
            p.writeInt(activate ? 0 : 1);

            send(serial, code, p);
        }

        @Override
        public void getCDMASubscription(int serial)
                throws android.os.RemoteException {

            Rlog.e(TAG, "getCDMASubscription called!");
        }

        @Override
        public void writeSmsToRuim(int serial, CdmaSmsWriteArgs cdmaSms)
                throws android.os.RemoteException {

            Rlog.e(TAG, "writeSmsToRuim called!");
        }

        @Override
        public void deleteSmsOnRuim(int serial, int index)
                throws android.os.RemoteException {

            Rlog.e(TAG, "deleteSmsOnRuim called!");
        }

        @Override
        public void getDeviceIdentity(int serial)
                throws android.os.RemoteException {

            Rlog.d(TAG, "getDeviceIdentity called!");

            int code = RIL_REQUEST_DEVICE_IDENTITY;

            Parcel p = getRequest(code, serial);

            send(serial, code, p);
        }

        @Override
        public void exitEmergencyCallbackMode(int serial)
                throws android.os.RemoteException {

            Rlog.e(TAG, "exitEmergencyCallbackMode called!");
        }

        @Override
        public void getSmscAddress(int serial)
                throws android.os.RemoteException {

            Rlog.e(TAG, "getSmscAddress called!");
        }

        @Override
        public void setSmscAddress(int serial, String smsc)
                throws android.os.RemoteException {

            Rlog.e(TAG, "setSmscAddress called!");
        }

        @Override
        public void reportSmsMemoryStatus(int serial, boolean available)
                throws android.os.RemoteException {

            Rlog.e(TAG, "reportSmsMemoryStatus called!");
        }

        @Override
        public void reportStkServiceIsRunning(int serial)
                throws android.os.RemoteException {

            Rlog.d(TAG, "reportStkServiceIsRunning called!");

            int code = RIL_REQUEST_REPORT_STK_SERVICE_IS_RUNNING;

            Parcel p = getRequest(code, serial);

            send(serial, code, p);
        }

        @Override
        public void getCdmaSubscriptionSource(int serial)
                throws android.os.RemoteException {

            Rlog.d(TAG, "getCdmaSubscriptionSource called!");

            int code = RIL_REQUEST_CDMA_GET_SUBSCRIPTION_SOURCE;

            Parcel p = getRequest(code, serial);

            send(serial, code, p);
        }

        @Override
        public void requestIsimAuthentication(int serial, String challenge)
                throws android.os.RemoteException {

            Rlog.e(TAG, "requestIsimAuthentication called!");
        }

        @Override
        public void acknowledgeIncomingGsmSmsWithPdu(int serial, boolean success, String ackPdu)
                throws android.os.RemoteException {

            Rlog.d(TAG, "acknowledgeIncomingGsmSmsWithPdu called!");

            int code = RIL_REQUEST_ACKNOWLEDGE_INCOMING_GSM_SMS_WITH_PDU;

            Parcel p = getRequest(code, serial);

            p.writeInt(2);
            p.writeString(success ? "1" : "0");
            p.writeString(ackPdu);

            send(serial, code, p);
        }

        @Override
        public void sendEnvelopeWithStatus(int serial, String contents)
                throws android.os.RemoteException {

            Rlog.e(TAG, "sendEnvelopeWithStatus called!");
        }

        @Override
        public void getVoiceRadioTechnology(int serial)
                throws android.os.RemoteException {

            Rlog.d(TAG, "getVoiceRadioTechnology called!");

            int code = RIL_REQUEST_VOICE_RADIO_TECH;

            Parcel p = getRequest(code, serial);

            send(serial, code, p);
        }

        @Override
        public void getCellInfoList(int serial)
                throws android.os.RemoteException {

            Rlog.d(TAG, "getCellInfoList called!");

            int code = RIL_REQUEST_GET_CELL_INFO_LIST;

            Parcel p = getRequest(code, serial);

            send(serial, code, p);
        }

        @Override
        public void setCellInfoListRate(int serial, int rate)
                throws android.os.RemoteException {

            Rlog.d(TAG, "setCellInfoListRate called!");

            int code = RIL_REQUEST_SET_UNSOL_CELL_INFO_LIST_RATE;

            Parcel p = getRequest(code, serial);

            p.writeInt(1);
            p.writeInt(rate);

            send(serial, code, p);
        }

        @Override
        public void setInitialAttachApn(int serial, DataProfileInfo dataProfileInfo, boolean modemCognitive, boolean isRoaming)
                throws android.os.RemoteException {

            Rlog.e(TAG, "setInitialAttachApn called!");
        }

        @Override
        public void getImsRegistrationState(int serial)
                throws android.os.RemoteException {

            Rlog.d(TAG, "getImsRegistrationState called!");

            int code = RIL_REQUEST_IMS_REGISTRATION_STATE;

            Parcel p = getRequest(code, serial);

            send(serial, code, p);
        }

        @Override
        public void sendImsSms(int serial, ImsSmsMessage message)
                throws android.os.RemoteException {

            Rlog.e(TAG, "sendImsSms called!");
        }

        @Override
        public void iccTransmitApduBasicChannel(int serial, SimApdu message)
                throws android.os.RemoteException {

            Rlog.e(TAG, "iccTransmitApduBasicChannel called!");
        }

        @Override
        public void iccOpenLogicalChannel(int serial, String aid, int p2)
                throws android.os.RemoteException {

            Rlog.d(TAG, "iccOpenLogicalChannel called!");

            int code = RIL_REQUEST_SIM_OPEN_CHANNEL;

            Parcel p = getRequest(code, serial);

            p.writeString(STRING_TORIL_NULL_HANDLED(aid));

            send(serial, code, p);
        }

        @Override
        public void iccCloseLogicalChannel(int serial, int channelId)
                throws android.os.RemoteException {

            Rlog.e(TAG, "iccCloseLogicalChannel called!");
        }

        @Override
        public void iccTransmitApduLogicalChannel(int serial, SimApdu message)
                throws android.os.RemoteException {

            Rlog.e(TAG, "iccTransmitApduLogicalChannel called!");
        }

        @Override
        public void nvReadItem(int serial, int itemId)
                throws android.os.RemoteException {

            Rlog.e(TAG, "nvReadItem called!");
        }

        @Override
        public void nvWriteItem(int serial, NvWriteItem item)
                throws android.os.RemoteException {

            Rlog.e(TAG, "nvWriteItem called!");
        }

        @Override
        public void nvWriteCdmaPrl(int serial, java.util.ArrayList<Byte> prl)
                throws android.os.RemoteException {

            Rlog.e(TAG, "nvWriteCdmaPrl called!");
        }

        @Override
        public void nvResetConfig(int serial, int resetType)
                throws android.os.RemoteException {

            Rlog.e(TAG, "nvResetConfig called!");
        }

        @Override
        public void setUiccSubscription(int serial, SelectUiccSub uiccSub)
                throws android.os.RemoteException {

            Rlog.e(TAG, "setUiccSubscription called!");
        }

        @Override
        public void setDataAllowed(int serial, boolean allow)
                throws android.os.RemoteException {

            Rlog.d(TAG, "setDataAllowed called!");

            int code = RIL_REQUEST_ALLOW_DATA;

            Parcel p = getRequest(code, serial);

            p.writeInt(1);
            p.writeInt(allow ? 1 : 0);

            send(serial, code, p);
        }

        @Override
        public void getHardwareConfig(int serial)
                throws android.os.RemoteException {

            Rlog.e(TAG, "getHardwareConfig called!");
        }

        @Override
        public void requestIccSimAuthentication(int serial, int authContext, String authData, String aid)
                throws android.os.RemoteException {

            Rlog.e(TAG, "requestIccSimAuthentication called!");
        }

        @Override
        public void setDataProfile(int serial, java.util.ArrayList<DataProfileInfo> profiles, boolean isRoaming)
                throws android.os.RemoteException {

            Rlog.e(TAG, "setDataProfile called!");
        }

        @Override
        public void requestShutdown(int serial)
                throws android.os.RemoteException {

            Rlog.e(TAG, "requestShutdown called!");
        }

        @Override
        public void getRadioCapability(int serial)
                throws android.os.RemoteException {

            Rlog.d(TAG, "getRadioCapability called!");

            int code = RIL_REQUEST_GET_RADIO_CAPABILITY;

            Parcel p = getRequest(code, serial);

            send(serial, code, p);
        }

        @Override
        public void setRadioCapability(int serial, RadioCapability rc)
                throws android.os.RemoteException {

            Rlog.e(TAG, "setRadioCapability called!");
        }

        @Override
        public void startLceService(int serial, int reportInterval, boolean pullMode)
                throws android.os.RemoteException {

            Rlog.d(TAG, "startLceService called!");

            int code = RIL_REQUEST_START_LCE;

            Parcel p = getRequest(code, serial);

            p.writeInt(2);
            p.writeInt(reportInterval);
            p.writeInt(pullMode ? 1: 0);  // PULL mode: 1; PUSH mode: 0;

            send(serial, code, p);
        }

        @Override
        public void stopLceService(int serial)
                throws android.os.RemoteException {

            Rlog.e(TAG, "stopLceService called!");

            int code = RIL_REQUEST_STOP_LCE;

            Parcel p = getRequest(code, serial);

            send(serial, code, p);
        }

        @Override
        public void pullLceData(int serial)
                throws android.os.RemoteException {

            Rlog.e(TAG, "pullLceData called!");
        }

        @Override
        public void getModemActivityInfo(int serial)
                throws android.os.RemoteException {

            Rlog.d(TAG, "getModemActivityInfo called!");

            int code = RIL_REQUEST_GET_ACTIVITY_INFO;

            Parcel p = getRequest(code, serial);

            send(serial, code, p);
        }

        @Override
        public void setAllowedCarriers(int serial, boolean allAllowed, CarrierRestrictions carriers)
                throws android.os.RemoteException {

            Rlog.e(TAG, "setAllowedCarriers called!");
        }

        @Override
        public void getAllowedCarriers(int serial)
                throws android.os.RemoteException {

            Rlog.e(TAG, "getAllowedCarriers called!");
        }

        private RadioResponseInfo createRadioResponseInfo(int serial, int error)
        {
            RadioResponseInfo responseInfo = new RadioResponseInfo();
            responseInfo.type = RESPONSE_SOLICITED;
            responseInfo.serial = serial;
            responseInfo.error = error;

            if (error != RILConstants.SUCCESS)
                Rlog.w(TAG, "createRadioResponseInfo: error : " + responseInfo.error);

            return responseInfo;
        }

        @Override
        public void sendDeviceState(int serial, int deviceStateType, boolean state)
                throws android.os.RemoteException {

            Rlog.d(TAG, "sendDeviceState called!");

            IRadioResponse radioResponse = mRadioResponse;
            if (radioResponse != null){
                radioResponse.sendDeviceStateResponse(createRadioResponseInfo(serial, RILConstants.REQUEST_NOT_SUPPORTED));
            }
        }

        @Override
        public void setIndicationFilter(int serial, int indicationFilter)
                throws android.os.RemoteException {

            Rlog.d(TAG, "setIndicationFilter called!");

            IRadioResponse radioResponse = mRadioResponse;
            if (radioResponse != null){
                radioResponse.setIndicationFilterResponse(createRadioResponseInfo(serial, RILConstants.REQUEST_NOT_SUPPORTED));
            }
        }

        @Override
        public void setSimCardPower(int serial, boolean powerUp)
                throws android.os.RemoteException {

            Rlog.e(TAG, "setSimCardPower called!");
        }

        @Override
        public void responseAcknowledgement()
                throws android.os.RemoteException {

            Rlog.e(TAG, "responseAcknowledgement called!");
        }
    }

    class OemHookImpl extends IOemHook.Stub
    {
        private static final boolean DEBUG = true;
        private static final String TAG = "RildService.OemHookImpl";

        @Override
        public void setResponseFunctions(IOemHookResponse oemHookResponse, IOemHookIndication oemHookIndication)
                throws android.os.RemoteException {

            Rlog.d(TAG, "setResponseFunctions called!");

            mOemHookResponse = oemHookResponse;
            mOemHookIndication = oemHookIndication;

            tryConnectRil();
        }

        @Override
        public void sendRequestRaw(int serial, java.util.ArrayList<Byte> data)
                throws android.os.RemoteException {
            Rlog.d(TAG, "sendRequestRaw called!");

            int code = RIL_REQUEST_OEM_HOOK_RAW;

            Parcel p = getRequest(code, serial);

            byte[] buffer = new byte[data.size()];
            for (int i = 0; i<buffer.length; i++)
                buffer[i] = data.get(i);

            p.writeByteArray(buffer);

            send(serial, code, p);
        }

        @Override
        public void sendRequestStrings(int serial, java.util.ArrayList<String> data)
                throws android.os.RemoteException {

            Rlog.d(TAG, "sendRequestStrings called!");

            int code = RIL_REQUEST_OEM_HOOK_STRINGS;

            Parcel p = getRequest(code, serial);

            String[] buffer = new String[data.size()];
            for (int i = 0; i<buffer.length; i++)
                buffer[i] = STRING_TORIL_NULL_HANDLED(data.get(i));

            p.writeStringArray(buffer);

            send(serial, code, p);
        }

    }

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (DEBUG) Rlog.d(TAG, "Starting service");

       // Rlog.d(TAG, "Waiting for debugger...");
       // android.os.Debug.waitForDebugger();
       // Rlog.d(TAG, "Debugger attached...");

        RadioImpl radio = new RadioImpl();

        String serviceName = "slot" + (mInstanceId + 1);

        try {
            radio.registerAsService(serviceName);

            Rlog.d(TAG, "radio service for " + serviceName + " registered!");
        } catch (android.os.RemoteException ex) {
            Rlog.e(TAG, "Error registerAsService radio: " + ex);
        }

        OemHookImpl oemHook = new OemHookImpl();


        try {
            oemHook.registerAsService(serviceName);

            Rlog.d(TAG, "oemHook service for " + serviceName + " registered!");
        } catch (android.os.RemoteException ex) {
            Rlog.e(TAG, "Error registerAsService oemHook: " + ex);
        }
    }
}
