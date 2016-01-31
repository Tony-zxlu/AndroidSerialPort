package android_serialport_api;

import com.ibrobo.R;
import com.ibrobo.robotbingo.utils.ByteUtils;
import com.ibrobo.robotbingo.utils.LogUtils;
import com.ibrobo.robotbingo.utils.RobotManager;
import com.ibrobo.robotbingo.utils.ToastUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.InvalidParameterException;
import java.util.Arrays;

/**
 * 文 件 名：SerialPortManager.java
 * 描    述：通过串口和底盘通信的管理类
 * 作    者：鲁中需
 * 时    间：2015/10/28
 * 版    本：1.0
 */
public class SerialPortManager {
    /**
     * 串口名称
     */
    public static final String SERIAL_PORT_PATH = "dev/ttyMT1";
    /**
     * 波特率
     */
    public static final int SERIAL_PORT_BAUDRATE = 115200;

    /**
     * 每条命令的数据长度20
     */
    public static final int DATA_SIZE = 20;

    private static final String TAG = SerialPortManager.class.getSimpleName();

    private SerialPort mSerialPort;
    private OutputStream mOutputStream;
    private InputStream mInputStream;
    private ReadThread mReadThread;

    private SerialPortManager() {
    }

    public static SerialPortManager mInstance = null;

    public static SerialPortManager getInstance() {
        if (mInstance == null) {
            mInstance = new SerialPortManager();
        }
        return mInstance;
    }

    private boolean readThreadFlag;


    // 1.定义一个包的最大长度
    int maxLength = 128;
    byte[] buffer = new byte[128];
    // 3.当前已经收到包的总长度
    int currentLength = 0;
    // 4.协议头长度3个字节（开始符1，类型1，长度1）
    int headerLength = 3;

    /**
     * 读取串口返回数据的线程
     */
    private class ReadThread extends Thread {

        @Override
        public void run() {
            super.run();
            while (!isInterrupted() && readThreadFlag) {
                try {
                    if (mInputStream == null) return;
                    int available = mInputStream.available();
                    if (available >= headerLength) {
                        LogUtils.d(" available : " + available);
                        // 防止超出数组最大长度导致溢出
                        if (available > maxLength - currentLength) {
                            available = maxLength - currentLength;
                        }
                        mInputStream.read(buffer, currentLength, available);
                        currentLength += available;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    LogUtils.e(e.toString());
                }

                int cursor = 0;
                // 如果当前收到包大于头的长度，则解析当前包
                while (currentLength >= headerLength) {
                    // 取到头部第一个字节，如果不是起始标志位continue
                    if (buffer[cursor] != (byte) 0x88) {
                        --currentLength;
                        ++cursor;
                        continue;
                    }
                    int contentLenght = parseDataLen(buffer, cursor, headerLength);
                    // 如果内容包的长度大于最大内容长度或者小于等于0，则说明这个包有问题，丢弃
                    if (contentLenght <= 0 || contentLenght > maxLength - 4) {
                        currentLength = 0;
                        break;
                    }
                    // 如果当前获取到长度小于整个包的长度，则跳出循环等待继续接收数据
                    int factPackLen = contentLenght + 4;
                    if (currentLength < factPackLen) {
                        break;
                    }
                    // 一个完整包即产生
                    onAssemblePacket(buffer, cursor, factPackLen);
                    currentLength -= factPackLen;
                    cursor += factPackLen;
                }

                // 残留字节移到缓冲区首
                if (currentLength > 0 && cursor > 0) {
                    System.arraycopy(buffer, cursor, buffer, 0, currentLength);
                }
            }
        }
    }

    private void onAssemblePacket(byte[] buffer, int index, int packlen) {
        byte[] buf = new byte[packlen];
        System.arraycopy(buffer, index, buf, 0, packlen);
        RobotManager.getInstance().getMotorState(buf);
        LogUtils.d(" receive packet : " + ByteUtils.showByteArray(buf));
        if (mCallback != null) {
            mCallback.onDataReceived(buffer, packlen);
        }
    }

    /**
     * 解析帧数据域的长度
     *
     * @param buffer
     * @param index
     * @param headerLength
     * @return
     */
    private int parseDataLen(byte[] buffer, int index, int headerLength) {
        if ((buffer.length - index) > headerLength) {
            byte startFlag = buffer[index];
            byte type = buffer[index + 1];
            byte dateLen = buffer[index + 2];
            LogUtils.i(" frame , startFlag : " + Integer.toHexString(startFlag & 0xFF) + " type : " + Integer.toHexString(type & 0xFF) + " dateLen : " + dateLen);
            return dateLen;
        }
        return -1;
    }


    /**
     * 初始化串口
     */
    public void init() {
            /* Check parameters */
        if ((SERIAL_PORT_PATH.length() == 0) || (SERIAL_PORT_BAUDRATE == -1)) {
            throw new InvalidParameterException();
        }
            /* Open the serial port */
        try {
            mSerialPort = new SerialPort(new File(SERIAL_PORT_PATH), SERIAL_PORT_BAUDRATE, 0);
            if (mSerialPort != null) {
                mInputStream = mSerialPort.getInputStream();
                mOutputStream = mSerialPort.getOutputStream();
                /* Create a receiving thread */
                readThreadFlag = true;
                mReadThread = new ReadThread();
                mReadThread.start();
                LogUtils.d("串口初始化成功!");
            } else {
                ToastUtils.showShort("串口初始化失败!");
            }
        } catch (SecurityException e) {
            ToastUtils.showShort(R.string.error_security);
        } catch (IOException e) {
            ToastUtils.showShort(R.string.error_unknown);
        } catch (InvalidParameterException e) {
            ToastUtils.showShort(R.string.error_configuration);
        }
    }

    private ReceiveDataCallBack mCallback = null;

    public interface ReceiveDataCallBack {
        void onDataReceived(byte[] buffer, int len);
    }

    /**
     * 设置串口数据返回的回调
     *
     * @param mCallback
     */
    public void setReceiveDataCallback(ReceiveDataCallBack mCallback) {
        this.mCallback = mCallback;
    }

    /**
     * 使用串口给底盘发送数据
     *
     * @param bytes
     */
    public void sendDataBySerialPort(byte[] bytes) {
        if (mOutputStream == null) {
            return;
        }
        try {
            LogUtils.d(" sendDataBySerialPort : bytes = " + Arrays.toString(bytes));
            mOutputStream.write(bytes);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    /**
     * 销毁串口相关的资源
     */
    public void destory() {
        if (mSerialPort != null) {
            mSerialPort.close();
            mSerialPort = null;
        }
        readThreadFlag = false;
        mCallback = null;
    }
}
