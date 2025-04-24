package in.girish.datagram;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.util.Arrays;
import java.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;

import android.os.Build;
import androidx.annotation.RequiresApi;
import android.util.Log;
import android.util.SparseArray;

public class Datagram extends CordovaPlugin {
	private static final String TAG = Datagram.class.getSimpleName();
    private static final int BUFFER_SIZE = 20480;

    private SparseArray<DatagramSocket> m_sockets;
    private SparseArray<SocketListener> m_listeners;
    private boolean isDestroyed = false;

    public Datagram() {
        m_sockets = new SparseArray<DatagramSocket>();
        m_listeners = new SparseArray<SocketListener>();
    }

    private class SocketListener extends Thread {
        int m_socketId;
        DatagramSocket m_socket;
        volatile boolean isRunning = true;

        public SocketListener(int id, DatagramSocket socket) {
            this.m_socketId = id;
            this.m_socket = socket;
        }

        @RequiresApi(api = Build.VERSION_CODES.O)
        public void run() {
            DatagramPacket packet = new DatagramPacket(new byte[BUFFER_SIZE], BUFFER_SIZE);
            Base64.Encoder encoder = Base64.getEncoder();
            
            while (isRunning && !isDestroyed) {
                try {
                    if (m_socket == null || m_socket.isClosed()) {
                        Log.d(TAG, "Socket closed, stopping listener for socketId: " + m_socketId);
                        return;
                    }
                    
                    this.m_socket.receive(packet);
                    byte[] data = Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getLength());
                    String base64Msg = encoder.encodeToString(data);
                    String address = packet.getAddress().getHostAddress();
                    int port = packet.getPort();

                    Datagram.this.webView.sendJavascript(
                        "cordova.require('cordova-plugin-udp.datagram')._onMessage("
                            + this.m_socketId + ","
                            + "`" + base64Msg + "`,"
                            + "'" + address + "',"
                            + port + ")");
                    Log.d(TAG, "RCVD: " + packet.getSocketAddress() + "  " + base64Msg);
                } catch (Exception e) {
                    if (isRunning && !isDestroyed) {
                        Log.d(TAG, "Receive exception for socketId " + m_socketId + ": " + e.toString());
                    }
                    return;
                }
            }
        }

        public void stopListening() {
            isRunning = false;
            if (m_socket != null && !m_socket.isClosed()) {
                m_socket.close();
            }
        }
    }

    private void closeAndRemove(int id) {
        SocketListener listener = m_listeners.get(id);
        if (listener != null) {
            listener.stopListening();
            m_listeners.remove(id);
        }

        DatagramSocket socket = m_sockets.get(id);
        if (socket != null) {
            try {
                if (!socket.isClosed()) {
                    socket.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error closing socket " + id + ": " + e.toString());
            }
            m_sockets.remove(id);
        }
    }

    private void cleanupAllSockets() {
        for (int i = 0; i < m_sockets.size(); i++) {
            int key = m_sockets.keyAt(i);
            closeAndRemove(key);
        }
        m_sockets.clear();
        m_listeners.clear();
    }

    @Override
    public void onDestroy() {
        isDestroyed = true;
        cleanupAllSockets();
        super.onDestroy();
    }

    @Override
    public void onReset() {
        cleanupAllSockets();
        super.onReset();
    }

    @Override
    public boolean execute(String action, JSONArray data, CallbackContext callbackContext) throws JSONException {
        if (isDestroyed) {
            callbackContext.error("Plugin is destroyed");
            return true;
        }

        final int id = data.getInt(0);
        DatagramSocket socket = m_sockets.get(id);

        if (action.equals("create")) {
            if (socket != null) {
                this.closeAndRemove(id);
            }
            final boolean isMulticast = data.getBoolean(1);
            try {
                socket = isMulticast ? new MulticastSocket(null) : new DatagramSocket(null);
                m_sockets.put(id, socket);
                callbackContext.success();
            } catch (Exception e) {
                Log.d(TAG, "Create exception:" + e.toString());
                callbackContext.error(e.toString());
            }
        } else if (action.equals("bind")) {
            if (socket == null) {
                callbackContext.error("Socket not created");
                return true;
            }

            // Check if there's already a listener for this socket
            SocketListener existingListener = m_listeners.get(id);
            if (existingListener != null) {
                existingListener.stopListening();
                m_listeners.remove(id);
            }

            final int port = data.getInt(1);
            try {
                socket.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), port));

                SocketListener listener = new SocketListener(id, socket);
                m_listeners.put(id, listener);
                listener.start();

                callbackContext.success();
            } catch (Exception e) {
                Log.d(TAG, "Bind exception:" + e.toString());
                callbackContext.error(e.toString());
            }
        } else if (action.equals("joinGroup")) {
            if (socket == null) {
                callbackContext.error("Socket not created");
                return true;
            }
            final String address = data.getString(1);
            MulticastSocket msocket = (MulticastSocket) socket;
            try {
                msocket.joinGroup(InetAddress.getByName(address));
                callbackContext.success();
            } catch (Exception e) {
                Log.d(TAG, "joinGroup exception:" + e.toString());
                callbackContext.error(e.toString());
            }
        } else if (action.equals("leaveGroup")) {
            if (socket == null) {
                callbackContext.error("Socket not created");
                return true;
            }
            final String address = data.getString(1);
            MulticastSocket msocket = (MulticastSocket) socket;
            try {
                msocket.leaveGroup(InetAddress.getByName(address));
                callbackContext.success();
            } catch (Exception e) {
                Log.d(TAG, "leaveGroup exception:" + e.toString());
                callbackContext.error(e.toString());
            }
        } else if (action.equals("send")) {
            if (socket == null) {
                callbackContext.error("Socket not created");
                return true;
            }
            String message = data.getString(1);
            String address = data.getString(2);
            int port = data.getInt(3);

            try {
                byte[] bytes = message.getBytes();
                DatagramPacket packet = new DatagramPacket(bytes, bytes.length, InetAddress.getByName(address), port);
                socket.send(packet);
                callbackContext.success(message);
            } catch (IOException ioe) {
                Log.d(TAG, "send exception:" + ioe.toString());
                callbackContext.error("IOException: " + ioe.toString());
            }
        } else if (action.equals("close")) {
            this.closeAndRemove(id);
            callbackContext.success();
        } else {
            return false; // 'MethodNotFound'
        }

        return true;
    }
}