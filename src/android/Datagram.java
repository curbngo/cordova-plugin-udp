package in.girish.datagram;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.os.Build;
import android.util.Log;
import android.util.SparseArray;

public class Datagram extends CordovaPlugin {
    private static final String TAG = Datagram.class.getSimpleName();
    private static final int BUFFER_SIZE = 20480;
    // Flush batched packets once per frame (~16 ms at 60 Hz) or when the batch fills up.
    private static final int BATCH_FLUSH_MS = 16;
    private static final int MAX_BATCH_SIZE = 50;
    // Cached once; avoids a module-registry lookup on every inbound packet.
    private static final String JS_MODULE_REF = "cordova.require('cordova-plugin-udp.datagram')";

    // Guarded by m_lock for all reads and writes.
    private final SparseArray<DatagramSocket> m_sockets = new SparseArray<>();
    private final SparseArray<SocketListener> m_listeners = new SparseArray<>();
    private final Object m_lock = new Object();
    private volatile boolean isDestroyed = false;

    // -------------------------------------------------------------------------
    // SocketListener — one daemon thread per socket
    // -------------------------------------------------------------------------

    private class SocketListener extends Thread {
        final int m_socketId;
        final DatagramSocket m_socket;
        volatile boolean isRunning = true;

        SocketListener(int id, DatagramSocket socket) {
            this.m_socketId = id;
            this.m_socket = socket;
            setName("UDP-Listener-" + id);
            setDaemon(true);
        }

        @Override
        public void run() {
            DatagramPacket packet = new DatagramPacket(new byte[BUFFER_SIZE], BUFFER_SIZE);
            List<JSONObject> batch = new ArrayList<>();

            // setSoTimeout drives the batch-flush cadence: receive() returns via
            // SocketTimeoutException every BATCH_FLUSH_MS ms even when there is no data,
            // giving us a reliable opportunity to flush whatever has accumulated.
            try {
                m_socket.setSoTimeout(BATCH_FLUSH_MS);
            } catch (SocketException e) {
                Log.e(TAG, "setSoTimeout failed for socketId " + m_socketId + ": " + e);
            }

            while (isRunning && !isDestroyed) {
                try {
                    if (m_socket.isClosed()) {
                        break;
                    }

                    m_socket.receive(packet);

                    byte[] data = Arrays.copyOfRange(
                        packet.getData(),
                        packet.getOffset(),
                        packet.getOffset() + packet.getLength()
                    );

                    JSONObject entry = new JSONObject();
                    try {
                        entry.put("id",   m_socketId);
                        entry.put("msg",  encodeBase64(data));
                        entry.put("addr", packet.getAddress().getHostAddress());
                        entry.put("port", packet.getPort());
                    } catch (JSONException je) {
                        Log.e(TAG, "JSON encoding error for socketId " + m_socketId + ": " + je);
                        continue;
                    }

                    batch.add(entry);

                    if (batch.size() >= MAX_BATCH_SIZE) {
                        flushBatch(batch);
                    }

                } catch (SocketTimeoutException ste) {
                    // Normal cadence tick — flush whatever accumulated.
                    if (!batch.isEmpty()) {
                        flushBatch(batch);
                    }
                } catch (Exception e) {
                    if (isRunning && !isDestroyed) {
                        Log.e(TAG, "Receive exception for socketId " + m_socketId + ": " + e);
                    }
                    break;
                }
            }

            // Flush any remaining messages before the thread exits.
            if (!batch.isEmpty()) {
                flushBatch(batch);
            }
        }

        private void flushBatch(List<JSONObject> batch) {
            JSONArray arr = new JSONArray(batch);
            Datagram.this.webView.sendJavascript(
                JS_MODULE_REF + "._onMessages(" + arr.toString() + ")"
            );
            batch.clear();
        }

        void stopListening() {
            isRunning = false;
            // Closing the socket unblocks any pending receive() call.
            if (!m_socket.isClosed()) {
                m_socket.close();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** API-level-safe Base64 encoder. java.util.Base64 requires API 26+. */
    private static String encodeBase64(byte[] data) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return java.util.Base64.getEncoder().encodeToString(data);
        } else {
            return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
        }
    }

    /**
     * Remove a socket and its listener atomically from the maps (under m_lock),
     * then close both outside the lock to avoid holding the lock during I/O.
     */
    private void closeAndRemove(int id) {
        SocketListener listener;
        DatagramSocket socket;

        synchronized (m_lock) {
            listener = m_listeners.get(id);
            m_listeners.remove(id);
            socket = m_sockets.get(id);
            m_sockets.remove(id);
        }

        // Stop the thread first — this closes the socket, which unblocks receive().
        if (listener != null) {
            listener.stopListening();
        }

        // Belt-and-suspenders: close independently in case no listener was attached.
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing socket " + id + ": " + e);
            }
        }
    }

    /**
     * Snapshot the current key set under the lock, then close each socket outside
     * the lock. This avoids the SparseArray mutation-during-iteration bug that
     * caused every other socket to be skipped when closeAndRemove() modified the
     * array while the loop index was advancing.
     */
    private void cleanupAllSockets() {
        int[] keys;
        synchronized (m_lock) {
            keys = new int[m_sockets.size()];
            for (int i = 0; i < m_sockets.size(); i++) {
                keys[i] = m_sockets.keyAt(i);
            }
        }
        for (int key : keys) {
            closeAndRemove(key);
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Command dispatch
    // -------------------------------------------------------------------------

    @Override
    public boolean execute(String action, JSONArray data, CallbackContext callbackContext) throws JSONException {
        if (isDestroyed) {
            callbackContext.error("Plugin is destroyed");
            return true;
        }

        final int id = data.getInt(0);

        if (action.equals("create")) {
            final boolean isMulticast = data.getBoolean(1);
            // closeAndRemove is a no-op when the id is absent; safe to call unconditionally.
            closeAndRemove(id);
            try {
                DatagramSocket socket = isMulticast ? new MulticastSocket(null) : new DatagramSocket(null);
                synchronized (m_lock) {
                    m_sockets.put(id, socket);
                }
                callbackContext.success();
            } catch (Exception e) {
                Log.e(TAG, "Create exception: " + e);
                callbackContext.error(e.toString());
            }

        } else if (action.equals("bind")) {
            DatagramSocket socket;
            synchronized (m_lock) {
                socket = m_sockets.get(id);
            }
            if (socket == null) {
                callbackContext.error("Socket not created");
                return true;
            }

            // Stop any pre-existing listener for this socket outside the lock.
            SocketListener existingListener;
            synchronized (m_lock) {
                existingListener = m_listeners.get(id);
                m_listeners.remove(id);
            }
            if (existingListener != null) {
                existingListener.stopListening();
            }

            final int port = data.getInt(1);
            try {
                socket.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), port));
                SocketListener listener = new SocketListener(id, socket);
                synchronized (m_lock) {
                    m_listeners.put(id, listener);
                }
                listener.start();
                callbackContext.success();
            } catch (Exception e) {
                Log.e(TAG, "Bind exception: " + e);
                callbackContext.error(e.toString());
            }

        } else if (action.equals("joinGroup")) {
            DatagramSocket socket;
            synchronized (m_lock) {
                socket = m_sockets.get(id);
            }
            if (socket == null) {
                callbackContext.error("Socket not created");
                return true;
            }
            final String address = data.getString(1);
            try {
                ((MulticastSocket) socket).joinGroup(InetAddress.getByName(address));
                callbackContext.success();
            } catch (Exception e) {
                Log.e(TAG, "joinGroup exception: " + e);
                callbackContext.error(e.toString());
            }

        } else if (action.equals("leaveGroup")) {
            DatagramSocket socket;
            synchronized (m_lock) {
                socket = m_sockets.get(id);
            }
            if (socket == null) {
                callbackContext.error("Socket not created");
                return true;
            }
            final String address = data.getString(1);
            try {
                ((MulticastSocket) socket).leaveGroup(InetAddress.getByName(address));
                callbackContext.success();
            } catch (Exception e) {
                Log.e(TAG, "leaveGroup exception: " + e);
                callbackContext.error(e.toString());
            }

        } else if (action.equals("send")) {
            DatagramSocket socket;
            synchronized (m_lock) {
                socket = m_sockets.get(id);
            }
            if (socket == null) {
                callbackContext.error("Socket not created");
                return true;
            }
            String message = data.getString(1);
            String address = data.getString(2);
            int port = data.getInt(3);
            try {
                byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
                DatagramPacket packet = new DatagramPacket(
                    bytes, bytes.length, InetAddress.getByName(address), port
                );
                socket.send(packet);
                callbackContext.success();
            } catch (IOException ioe) {
                Log.e(TAG, "send exception: " + ioe);
                callbackContext.error("IOException: " + ioe.toString());
            }

        } else if (action.equals("close")) {
            closeAndRemove(id);
            callbackContext.success();

        } else {
            return false; // 'MethodNotFound'
        }

        return true;
    }
}
