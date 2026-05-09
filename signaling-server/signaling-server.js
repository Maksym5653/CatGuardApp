/**
 * CatGuard WebRTC Signaling Server
 * ================================
 * Run on any device in the local network (laptop, Raspberry Pi, etc.)
 * 
 * SETUP:
 *   npm install ws
 *   node signaling-server.js
 * 
 * Server listens on port 8080.
 * Update the IP in CameraActivity.kt and ViewerActivity.kt to match
 * the device running this server (e.g. ws://192.168.1.100:8080).
 * 
 * ROOM LOGIC:
 *   - Each client joins with ?room=XXXX&id=DEVICEID&role=camera|viewer
 *   - Messages are forwarded to all other clients in the same room
 *   - "peer_joined" / "peer_left" events are broadcast on connect/disconnect
 */

const WebSocket = require('ws');
const url = require('url');

const PORT = 8080;
const wss = new WebSocket.Server({ port: PORT });

// rooms: Map<roomCode, Map<deviceId, {ws, role}>>
const rooms = new Map();

console.log(`CatGuard Signaling Server running on ws://0.0.0.0:${PORT}`);

wss.on('connection', (ws, req) => {
    const params = new url.URLSearchParams(url.parse(req.url).query);
    const room = params.get('room');
    const deviceId = params.get('id') || Math.random().toString(36).substr(2, 8);
    const role = params.get('role') || 'unknown';

    if (!room) {
        ws.close(1008, 'Room code required');
        return;
    }

    console.log(`[+] ${role} ${deviceId} joined room ${room}`);

    // Join room
    if (!rooms.has(room)) rooms.set(room, new Map());
    const roomPeers = rooms.get(room);
    roomPeers.set(deviceId, { ws, role });

    // Notify existing peers that someone joined
    broadcast(room, deviceId, JSON.stringify({
        type: 'peer_joined',
        from: deviceId,
        role: role,
        room: room
    }));

    ws.on('message', (data) => {
        try {
            const msg = JSON.parse(data.toString());
            // Forward to all other peers in the room
            broadcast(room, deviceId, JSON.stringify(msg));
        } catch (e) {
            console.error('Invalid message:', e.message);
        }
    });

    ws.on('close', () => {
        console.log(`[-] ${role} ${deviceId} left room ${room}`);
        roomPeers.delete(deviceId);
        if (roomPeers.size === 0) rooms.delete(room);

        broadcast(room, deviceId, JSON.stringify({
            type: 'peer_left',
            from: deviceId,
            room: room
        }));
    });

    ws.on('error', (err) => {
        console.error(`WebSocket error for ${deviceId}: ${err.message}`);
    });
});

function broadcast(room, senderId, message) {
    const peers = rooms.get(room);
    if (!peers) return;
    peers.forEach(({ ws }, id) => {
        if (id !== senderId && ws.readyState === WebSocket.OPEN) {
            ws.send(message);
        }
    });
}
