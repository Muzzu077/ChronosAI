import asyncio
import os
import sys
from dotenv import load_dotenv

# Ensure we can import from current directory
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from livekit import rtc
import main

load_dotenv()

async def simulate():
    room = rtc.Room()
    
    @room.on("data_received")
    def on_data_received(data: rtc.DataPacket):
        print(f"[Client] Received data: {data.data.decode('utf-8')} from {data.participant.identity if data.participant else 'unknown'}")

    @room.on("track_subscribed")
    def on_track_subscribed(track, publication, participant):
        print(f"[Client] Subscribed to track {track.sid} of participant {participant.identity}")

    @room.on("participant_connected")
    def on_participant_connected(participant):
        print(f"[Client] Participant connected: {participant.identity}")

    # Generate token for the user
    import uuid
    user_id = str(uuid.uuid4())
    token_data = main.get_listen_token(user_id=user_id)
    token = token_data['token']
    url = token_data['server_url']
    
    print(f"[Client] Connecting client to room: {token_data['room_name']} at {url}")
    await room.connect(url, token)
    print("[Client] Connected successfully! Waiting for LiveKit agent to spawn and join the room...")
    
    # Wait for agent to spawn and join
    for i in range(15):
        await asyncio.sleep(2)
        # Check current participants in the room
        participants = room.remote_participants
        if participants:
            print(f"[Client] Current other participants in room: {[p.identity for p in participants.values()]}")
            
    print("[Client] Simulating sending a chat message to agent: 'Hi, what is my schedule today?'")
    # Send data message to the agent
    msg = "hii"
    local_part = room.local_participant
    if local_part:
        await local_part.publish_data(
            payload=msg,
            topic="chat"
        )
        print("[Client] Chat message published.")
        
    await asyncio.sleep(600)
    
    print("[Client] Disconnecting...")
    await room.disconnect()

if __name__ == '__main__':
    asyncio.run(simulate())
