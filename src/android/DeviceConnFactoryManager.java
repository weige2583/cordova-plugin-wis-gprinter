package com.isesol.wis;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.PluginResult;

import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.gprinter.io.BluetoothPort;
import com.gprinter.io.EthernetPort;
import com.gprinter.io.PortManager;
import com.gprinter.io.SerialPort;
import com.gprinter.io.UsbPort;
import com.gprinter.command.LabelCommand;



import java.io.IOException;
import java.util.HashMap;
import java.util.Vector;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Created by Administrator
 *
 * @author 猿史森林
 * Time 2017/8/2
 */
public class DeviceConnFactoryManager {

    public HashMap<Integer, LabelCommand> labels = new HashMap(20);

    public PortManager mPort;

    private static final String TAG = DeviceConnFactoryManager.class.getSimpleName();

    public CONN_METHOD connMethod;

    private String ip;

    private int port;

    private String macAddress;

    private UsbDevice mUsbDevice;

    private Context mContext;

    private String serialPortPath;

    private int baudrate;

    private int id;

    private static DeviceConnFactoryManager[] deviceConnFactoryManagers = new DeviceConnFactoryManager[4];

    private boolean isOpenPort;
    /**
     * ESC查询打印机实时状态指令
     */
    private byte[] esc = {0x10, 0x04, 0x02};

    /**
     * ESC查询打印机实时状态 缺纸状态
     */
    private static final int ESC_STATE_PAPER_ERR = 0x20;

    /**
     * ESC指令查询打印机实时状态 打印机开盖状态
     */
    private static final int ESC_STATE_COVER_OPEN = 0x04;

    /**
     * ESC指令查询打印机实时状态 打印机报错状态
     */
    private static final int ESC_STATE_ERR_OCCURS = 0x40;

    /**
     * TSC查询打印机状态指令
     */
    private byte[] tsc = {0x1b, '!', '?'};

    /**
     * TSC指令查询打印机实时状态 打印机缺纸状态
     */
    private static final int TSC_STATE_PAPER_ERR = 0x04;

    /**
     * TSC指令查询打印机实时状态 打印机开盖状态
     */
    private static final int TSC_STATE_COVER_OPEN = 0x01;

    /**
     * TSC指令查询打印机实时状态 打印机出错状态
     */
    private static final int TSC_STATE_ERR_OCCURS = 0x80;

    private byte[] cpcl = {0x1b, 0x68};

    /**
     * CPCL指令查询打印机实时状态 打印机缺纸状态
     */
    private static final int CPCL_STATE_PAPER_ERR = 0x01;
    /**
     * CPCL指令查询打印机实时状态 打印机开盖状态
     */
    private static final int CPCL_STATE_COVER_OPEN = 0x02;

    private byte[] sendCommand;
    /**
     * 判断打印机所使用指令是否是ESC指令
     */
    private PrinterCommand currentPrinterCommand;
    public static final byte FLAG = 0x10;
    private static final int READ_DATA = 10000;
    private static final int DEFAUIT_COMMAND = 20000;
    private static final String READ_DATA_CNT = "read_data_cnt";
    private static final String READ_BUFFER_ARRAY = "read_buffer_array";
    public static final String ACTION_CONN_STATE = "action_connect_state";
    public static final String ACTION_QUERY_PRINTER_STATE = "action_query_printer_state";
    public static final String STATE = "state";
    public static final String DEVICE_ID = "id";
    public static final int CONN_STATE_DISCONNECT = 0x90;
    public static final int CONN_STATE_CONNECTING = CONN_STATE_DISCONNECT << 1;
    public static final int CONN_STATE_FAILED = CONN_STATE_DISCONNECT << 2;
    public static final int CONN_STATE_CONNECTED = CONN_STATE_DISCONNECT << 3;
    public PrinterReader reader;
    private int queryPrinterCommandFlag;
    private final int ESC = 1;
    private final int TSC = 3;
    private final int CPCL = 2;

    private BluetoothDevice currentBluetoothDevice = null;
    private BluetoothAdapter bluetoothAdapter = null;
    private CallbackContext dataAvailableCallback = null;

    public enum CONN_METHOD {
        //蓝牙连接
        BLUETOOTH("BLUETOOTH"),
        //USB连接
        USB("USB"),
        //wifi连接
        WIFI("WIFI"),
        //串口连接
        SERIAL_PORT("SERIAL_PORT");

        private String name;

        private CONN_METHOD(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return this.name;
        }
    }

    public static DeviceConnFactoryManager[] getDeviceConnFactoryManagers() {
        return deviceConnFactoryManagers;
    }

    /**
     * 打开端口
     *
     * @return
     */
    public boolean openPort() {
        deviceConnFactoryManagers[id].isOpenPort = false;
        sendStateBroadcast(CONN_STATE_CONNECTING);
        switch (deviceConnFactoryManagers[id].connMethod) {
            case BLUETOOTH:
                mPort = new BluetoothPort(macAddress);
                isOpenPort = mPort.openPort();
                if (isOpenPort) {
                    currentBluetoothDevice = bluetoothAdapter.getRemoteDevice(macAddress);
                }
                break;
            case USB:
                mPort = new UsbPort(mContext, mUsbDevice);
                isOpenPort = mPort.openPort();
                break;
            case WIFI:
                mPort = new EthernetPort(ip, port);
                isOpenPort = mPort.openPort();
                break;
            case SERIAL_PORT:
                mPort = new SerialPort(serialPortPath, baudrate, 0);
                isOpenPort = mPort.openPort();
                break;
            default:
                mPort = new BluetoothPort(macAddress);
                isOpenPort = mPort.openPort();
                break;
        }

        //端口打开成功后，检查连接打印机所使用的打印机指令ESC、TSC
        if (isOpenPort) {
            queryCommand();
        } else {
            if (this.mPort != null) {
                this.mPort = null;
            }
            sendStateBroadcast(CONN_STATE_FAILED);
        }
        return isOpenPort;
    }

    /**
     * 查询当前连接打印机所使用打印机指令（ESC（EscCommand.java）、TSC（LabelCommand.java））
     */
    private void queryCommand() {
        //开启读取打印机返回数据线程
        reader = new PrinterReader();
        reader.start(); //读取数据线程
        //查询打印机所使用指令
//        queryPrinterCommand(); //小票机连接不上  注释这行，添加下面那三行代码。使用ESC指令
        sendCommand = tsc;
        currentPrinterCommand = PrinterCommand.TSC;
        sendStateBroadcast(CONN_STATE_CONNECTED);
    }

    /**
     * 获取端口连接方式
     *
     * @return
     */
    public CONN_METHOD getConnMethod() {
        return connMethod;
    }

    /**
     * 获取端口打开状态（true 打开，false 未打开）
     *
     * @return
     */
    public boolean getConnState() {
        return isOpenPort;
    }

    /**
     * 获取连接蓝牙的物理地址
     *
     * @return
     */
    public String getMacAddress() {
        return macAddress;
    }

    /**
     * 获取连接网口端口号
     *
     * @return
     */
    public int getPort() {
        return port;
    }

    /**
     * 获取连接网口的IP
     *
     * @return
     */
    public String getIp() {
        return ip;
    }

    /**
     * 获取连接的USB设备信息
     *
     * @return
     */
    public UsbDevice usbDevice() {
        return mUsbDevice;
    }

    /**
     * 关闭端口
     */
    public void closePort(int id) {
        if (this.mPort != null) {
            if (reader != null) {
                reader.cancel();
                reader = null;
            }
            boolean b = this.mPort.closePort();
            if (b) {
                this.mPort = null;
                isOpenPort = false;
                currentPrinterCommand = null;
            }
        }
        sendStateBroadcast(CONN_STATE_DISCONNECT);
    }

    /**
     * 获取串口号
     *
     * @return
     */
    public String getSerialPortPath() {
        return serialPortPath;
    }

    /**
     * 获取波特率
     *
     * @return
     */
    public int getBaudrate() {
        return baudrate;
    }

    public static void closeAllPort() {
        for (DeviceConnFactoryManager deviceConnFactoryManager : deviceConnFactoryManagers) {
            if (deviceConnFactoryManager != null) {
                Log.e(TAG, "cloaseAllPort() id -> " + deviceConnFactoryManager.id);
                deviceConnFactoryManager.closePort(deviceConnFactoryManager.id);
                deviceConnFactoryManagers[deviceConnFactoryManager.id] = null;
            }
        }
    }

    private DeviceConnFactoryManager(Build build) {
        this.connMethod = build.connMethod;
        this.macAddress = build.macAddress;
        this.port = build.port;
        this.ip = build.ip;
        this.mUsbDevice = build.usbDevice;
        this.mContext = build.context;
        this.dataAvailableCallback = build.callback;
        this.serialPortPath = build.serialPortPath;
        this.baudrate = build.baudrate;
        this.id = build.id;
        if (this.bluetoothAdapter == null) {
            this.bluetoothAdapter = build.bluetoothAdapter;
        }
        deviceConnFactoryManagers[id] = this;
    }

    /**
     * 获取当前打印机指令
     *
     * @return PrinterCommand
     */
    public PrinterCommand getCurrentPrinterCommand() {
        return deviceConnFactoryManagers[id].currentPrinterCommand;
    }

    public BluetoothDevice getCurrentBluetoothDevice() {
        return currentBluetoothDevice;
    }


    public static final class Build {
        private String ip;
        private String macAddress;
        private UsbDevice usbDevice;
        private int port;
        private CONN_METHOD connMethod;
        private Context context;
        private CallbackContext callback;
        private String serialPortPath;
        private int baudrate;
        private int id;
        private BluetoothAdapter bluetoothAdapter;

        public DeviceConnFactoryManager.Build setIp(String ip) {
            this.ip = ip;
            return this;
        }

        public DeviceConnFactoryManager.Build setMacAddress(String macAddress) {
            this.macAddress = macAddress;
            return this;
        }

        public DeviceConnFactoryManager.Build setUsbDevice(UsbDevice usbDevice) {
            this.usbDevice = usbDevice;
            return this;
        }

        public DeviceConnFactoryManager.Build setPort(int port) {
            this.port = port;
            return this;
        }
        public DeviceConnFactoryManager.Build setCallback(CallbackContext callback) {
            this.callback = callback;
            return this;
        }

        public DeviceConnFactoryManager.Build setConnMethod(CONN_METHOD connMethod) {
            this.connMethod = connMethod;
            return this;
        }

        public DeviceConnFactoryManager.Build setContext(Context context) {
            this.context = context;
            return this;
        }

        public DeviceConnFactoryManager.Build setId(int id) {
            this.id = id;
            return this;
        }

        public DeviceConnFactoryManager.Build setSerialPort(String serialPortPath) {
            this.serialPortPath = serialPortPath;
            return this;
        }

        public DeviceConnFactoryManager.Build setBaudrate(int baudrate) {
            this.baudrate = baudrate;
            return this;
        }

        public DeviceConnFactoryManager build() {

            this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            return new DeviceConnFactoryManager(this);
        }
    }

    public void sendDataImmediately(final Vector<Byte> data) throws IOException  {
        if (this.mPort == null) {
            return;
        }
        try {
            this.mPort.writeDataImmediately(data, 0, data.size());
        } catch (IOException e) {//异常中断发送
            mHandler.obtainMessage(Constant.abnormal_Disconnection).sendToTarget();
            e.printStackTrace();

        }
    }

    public void sendByteDataImmediately(final byte[] data) {
        if (this.mPort == null) {
            return;
        } else {
            Vector<Byte> datas = new Vector<Byte>();
            for (int i = 0; i < data.length; ++i) {
                datas.add(Byte.valueOf(data[i]));
            }
            try {
                this.mPort.writeDataImmediately(datas, 0, datas.size());
            } catch (IOException e) {//异常中断发送
                e.printStackTrace();
                mHandler.obtainMessage(Constant.abnormal_Disconnection).sendToTarget();
            }
        }
    }

    public int readDataImmediately(byte[] buffer) throws IOException {
        return this.mPort.readData(buffer);
    }

    /**
     * 查询打印机当前使用的指令（ESC、CPCL、TSC、）
     */
    private void queryPrinterCommand() {
        queryPrinterCommandFlag = ESC;
        ThreadPool.getInstantiation().addSerialTask(new Runnable() {
            @Override
            public void run() {
                //开启计时器，隔2000毫秒没有没返回值时发送查询打印机状态指令，先发票据，面单，标签
                final ThreadFactoryBuilder threadFactoryBuilder = new ThreadFactoryBuilder("Timer");
                final ScheduledExecutorService scheduledExecutorService = new ScheduledThreadPoolExecutor(1, threadFactoryBuilder);
                scheduledExecutorService.scheduleAtFixedRate(threadFactoryBuilder.newThread(new Runnable() {
                    @Override
                    public void run() {
                        if (currentPrinterCommand == null && queryPrinterCommandFlag > TSC) {
                            if (getConnMethod() == CONN_METHOD.USB) {//三种状态查询，完毕均无返回值，默认票据（针对凯仕、盛源机器USB查询指令没有返回值，导致连不上）
                                currentPrinterCommand = PrinterCommand.ESC;
                                sendStateBroadcast(CONN_STATE_CONNECTED);
                                sendCommand = esc;
                                mHandler.sendMessage(mHandler.obtainMessage(DEFAUIT_COMMAND, ""));
                                scheduledExecutorService.shutdown();
                            } else {
                                if (reader != null) {//三种状态，查询无返回值，发送连接失败广播
                                    reader.cancel();
                                    mPort.closePort();
                                    isOpenPort = false;
                                    sendStateBroadcast(CONN_STATE_FAILED);
                                    scheduledExecutorService.shutdown();
                                }
                            }
                        }
                        if (currentPrinterCommand != null) {
                            if (scheduledExecutorService != null && !scheduledExecutorService.isShutdown()) {
                                scheduledExecutorService.shutdown();
                            }
                            return;
                        }
                        switch (queryPrinterCommandFlag) {
                            case ESC:
                                //发送ESC查询打印机状态指令
                                sendCommand = esc;
                                break;
                            case TSC:
                                //发送ESC查询打印机状态指令
                                sendCommand = tsc;
                                break;
                            case CPCL:
                                //发送CPCL查询打印机状态指令
                                sendCommand = cpcl;
                                break;
                            default:
                                break;
                        }
                        Vector<Byte> data = new Vector<>(sendCommand.length);
                        for (int i = 0; i < sendCommand.length; i++) {
                            data.add(sendCommand[i]);
                        }
                        try {
                            sendDataImmediately(data);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        queryPrinterCommandFlag++;
                    }
                }), 1500, 1500, TimeUnit.MILLISECONDS);
            }
        });
    }

    class PrinterReader extends Thread {
        private boolean isRun = false;

        private byte[] buffer = new byte[100];

        public PrinterReader() {
            isRun = true;
        }

        @Override
        public void run() {
            try {
                while (isRun) {
                    //读取打印机返回信息,打印机没有返回纸返回-1
                    Log.e(TAG, "wait read ");
                    int len = readDataImmediately(buffer);
                    Log.e(TAG, " read " + len);
                    if (len > 0) {
                        Message message = Message.obtain();
                        message.what = READ_DATA;
                        Bundle bundle = new Bundle();
                        bundle.putInt(READ_DATA_CNT, len); //数据长度
                        bundle.putByteArray(READ_BUFFER_ARRAY, buffer); //数据
                        message.setData(bundle);
                        mHandler.sendMessage(message);
                    }
                }
            } catch (Exception e) {//异常断开
                if (deviceConnFactoryManagers[id] != null) {
                    closePort(id);
                    mHandler.obtainMessage(Constant.abnormal_Disconnection).sendToTarget();
                }
            }
        }

        public void cancel() {
            isRun = false;
        }
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case Constant.abnormal_Disconnection://异常断开连接
                    Utils.toast(mContext, "断开连接");
                    break;
                case DEFAUIT_COMMAND://默认模式
                    Utils.toast(mContext, "默认模式，标签模式");
                    break;
                case READ_DATA:
                    int cnt = msg.getData().getInt(READ_DATA_CNT); //数据长度 >0;
                    byte[] buffer = msg.getData().getByteArray(READ_BUFFER_ARRAY);  //数据
                    //这里只对查询状态返回值做处理，其它返回值可参考编程手册来解析
                    if (buffer == null) {
                        return;
                    }
                    int result = judgeResponseType(buffer[0]); //数据右移
                    String status = "打印机连接正常";
                    if (sendCommand == esc) {
                        //设置当前打印机模式为ESC模式
                        if (currentPrinterCommand == null) {
                            currentPrinterCommand = PrinterCommand.ESC;
                            sendStateBroadcast(CONN_STATE_CONNECTED);
                            Utils.toast(mContext,"票据模式");
                        } else {//查询打印机状态
                            if (result == 0) {//打印机状态查询
                                Intent intent = new Intent(ACTION_QUERY_PRINTER_STATE);
                                intent.putExtra(DEVICE_ID, id);
                                mContext.sendBroadcast(intent);
                            } else if (result == 1) {//查询打印机实时状态
                                if ((buffer[0] & ESC_STATE_PAPER_ERR) > 0) {
                                    status += " 打印机缺纸";
                                }
                                if ((buffer[0] & ESC_STATE_COVER_OPEN) > 0) {
                                    status += " 打印机开盖";
                                }
                                if ((buffer[0] & ESC_STATE_ERR_OCCURS) > 0) {
                                    status += " 打印机出错";
                                }
                                String mode = "打印模式:ESC";
                                Utils.toast(mContext, mode + " " + status);
                            }
                        }
                    } else if (sendCommand == tsc) {
                        //设置当前打印机模式为TSC模式
                        if (currentPrinterCommand == null) {
                            currentPrinterCommand = PrinterCommand.TSC;
                            sendStateBroadcast(CONN_STATE_CONNECTED);
                            Utils.toast(mContext, "标签模式");
                        } else {
                            if (cnt == 1) {//查询打印机实时状态
                                if ((buffer[0] & TSC_STATE_PAPER_ERR) > 0) {//缺纸
                                    status += " 打印机缺纸";
                                }
                                if ((buffer[0] & TSC_STATE_COVER_OPEN) > 0) {//开盖
                                    status += " 打印机开盖";
                                }
                                if ((buffer[0] & TSC_STATE_ERR_OCCURS) > 0) {//打印机报错
                                    status += " 打印机出错";
                                }
                                String mode = "打印模式:TSC";
                                Utils.toast(mContext, mode + " " + status);
                                if (dataAvailableCallback != null) {
                                    PluginResult cbResult = new PluginResult(PluginResult.Status.ERROR, mode + " " + status);
                                    cbResult.setKeepCallback(true);
                                    dataAvailableCallback.sendPluginResult(cbResult);
                                }
                            } else {//打印机状态查询
                                Intent intent = new Intent(ACTION_QUERY_PRINTER_STATE);
                                intent.putExtra(DEVICE_ID, id);
                                mContext.sendBroadcast(intent);
                            }
                        }
                    }else if(sendCommand==cpcl){
                        if (currentPrinterCommand == null) {
                            currentPrinterCommand = PrinterCommand.CPCL;
                            sendStateBroadcast(CONN_STATE_CONNECTED);
                            Utils.toast(mContext, "面单模式");
                        }else {
                            if (cnt == 1) {
                                if ((buffer[0] ==CPCL_STATE_PAPER_ERR)) {//缺纸
                                    status += " 打印机缺纸";
                                }
                                if ((buffer[0] ==CPCL_STATE_COVER_OPEN)) {//开盖
                                    status += " 打印机开盖";
                                }
                                String mode = "打印模式:CPCL";
                                Utils.toast(mContext, mode + " " + status);
                            } else {//打印机状态查询
                                Intent intent = new Intent(ACTION_QUERY_PRINTER_STATE);
                                intent.putExtra(DEVICE_ID, id);
                                mContext.sendBroadcast(intent);
                            }
                        }
                    }
                    break;
                default:
                    break;
            }
        }
    };

    /**
     * 发送广播
     *
     * @param state
     */
    private void sendStateBroadcast(int state) {
        Intent intent = new Intent(ACTION_CONN_STATE);
        intent.putExtra(STATE, state);
        intent.putExtra(DEVICE_ID, id);
        mContext.sendBroadcast(intent);//此处若报空指针错误，需要在清单文件application标签里注册此类，参考demo
    }

    /**
     * 判断是实时状态（10 04 02）还是查询状态（1D 72 01）
     */
    private int judgeResponseType(byte r) {
        return (byte) ((r & FLAG) >> 4);
    }


}