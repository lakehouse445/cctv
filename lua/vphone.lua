-- vphone.lua - voice AND video calls over rednet, reliable edition.
-- Media flows directly between the two stations (true P2P - the exchange
-- only routes call setup and the directory). Every video frame is
-- acknowledged by the receiver before the next is rendered, so the feed
-- self-paces to what the link actually carries instead of flooding it.
-- Audio rides a jitter buffer and drains through the speaker's own empty
-- events, so it survives lag spikes without crackle.
--
-- Exchange:  a computer + modem (ender modem for unlimited range)
--   vphone server
-- Station:   computer + camera + microphone + speaker + monitor + modem
--   vphone <yourname> [fps]     fps cap 1-20, default 10
--
-- Use ender modems (or one wired network) between distant stations:
-- plain wireless modems have limited range and drop everything beyond it.
--
-- Station keys: C = call someone, L = list stations, Q = quit.
-- While ringing: Y = answer, N = decline. In a call: hold H to hang up.

local args = { ... }
local mode = args[1] or error("Usage: vphone server | vphone <yourname> [fps]", 0)
local maxFps = math.min(20, math.max(1, tonumber(args[2]) or 10))

local VERSION = 2
local PROTOCOL = "cctv_vphone"
local EXCHANGE_PROTOCOL = "cctv_vphone_x"
local RING_TIMEOUT = 20
local HEARTBEAT = 5
local PRESENCE_EXPIRY = 15
local MAX_VIDEO_W, MAX_VIDEO_H = 162, 81
local LINK_LOST_AFTER = 10
local PREBUFFER_CHUNKS = 3   -- ~60ms of audio before playback starts
local MAX_BUFFER_CHUNKS = 25 -- ~0.5s; beyond this we drop old audio to stay live

-- Prefer the ender/wireless modem for the call network: a station usually
-- also has a wired modem for its own peripherals, and rednet must not end
-- up on that one.
local modem = peripheral.find("modem", function(_, m) return m.isWireless() end)
  or peripheral.find("modem")
  or error("No modem attached", 0)
rednet.open(peripheral.getName(modem))

-- ================= Exchange =================

if mode == "server" then
  rednet.host(EXCHANGE_PROTOCOL, "exchange")
  local stations = {}

  local function online()
    local now = os.clock()
    local result = {}
    for name, info in pairs(stations) do
      if now - info.last <= PRESENCE_EXPIRY then
        result[#result + 1] = { name = name, state = info.state }
      end
    end
    table.sort(result, function(a, b) return a.name < b.name end)
    return result
  end

  print("Videophone exchange running. Hold Ctrl+T to stop.")
  while true do
    local sender, message = rednet.receive(EXCHANGE_PROTOCOL)
    if type(message) == "table" then
      if message.type == "register" or message.type == "heartbeat" then
        stations[message.name] = { id = sender, state = message.state or "idle", last = os.clock() }
        if message.type == "register" then
          print(("%s registered (#%d)"):format(message.name, sender))
        end
      elseif message.type == "who" then
        rednet.send(sender, { type = "directory", stations = online() }, EXCHANGE_PROTOCOL)
      elseif message.type == "resolve" then
        local info = stations[message.name]
        local id = info and os.clock() - info.last <= PRESENCE_EXPIRY and info.id or nil
        rednet.send(sender, { type = "peer", name = message.name, id = id,
          state = info and info.state or nil }, EXCHANGE_PROTOCOL)
      end
    end
  end
end

-- ================= Station =================

local myName = mode
local mic = peripheral.find("microphone") or error("No microphone attached", 0)
local speaker = peripheral.find("speaker") or error("No speaker attached", 0)
local cam = peripheral.find("camera") or error("No camera attached", 0)
local mon = peripheral.find("monitor") or error("No monitor attached", 0)

mon.setTextScale(0.5)
local monW, monH = mon.getSize()

rednet.host(PROTOCOL, myName)
local exchangeId = rednet.lookup(EXCHANGE_PROTOCOL, "exchange")
local state = "idle"

local function tone(kind)
  if kind == "ring" then
    speaker.playNote("bit", 1, 18)
    speaker.playNote("bit", 1, 14)
  elseif kind == "ringback" then
    speaker.playNote("harp", 0.6, 12)
  elseif kind == "busy" then
    speaker.playNote("bit", 0.8, 6)
    speaker.playNote("bit", 0.8, 6)
  elseif kind == "click" then
    speaker.playNote("hat", 0.6, 12)
  end
end

local function heartbeat()
  if exchangeId then
    rednet.send(exchangeId, { type = "heartbeat", name = myName, state = state }, EXCHANGE_PROTOCOL)
  end
end

local function setState(next)
  state = next
  heartbeat()
end

local function encode(samples)
  local out = {}
  for i = 1, #samples do out[i] = string.char(samples[i] + 128) end
  return table.concat(out)
end

local function decode(data)
  local samples = {}
  for i = 1, #data do samples[i] = data:byte(i) - 128 end
  return samples
end

local function send(id, kind, extra)
  local message = extra or {}
  message.type = kind
  message.from = myName
  rednet.send(id, message, PROTOCOL)
end

local function resolve(name)
  if exchangeId then
    rednet.send(exchangeId, { type = "resolve", name = name }, EXCHANGE_PROTOCOL)
    local deadline = os.clock() + 2
    while os.clock() < deadline do
      local sender, message = rednet.receive(EXCHANGE_PROTOCOL, deadline - os.clock())
      if sender == exchangeId and type(message) == "table"
        and message.type == "peer" and message.name == name then
        return message.id, message.state
      end
    end
    return nil
  end
  return rednet.lookup(PROTOCOL, name)
end

local function clearMonitor(label)
  for i = 1, 16 do
    mon.setPaletteColour(2 ^ (i - 1), (i - 1) * 17 * 0x10101)
  end
  mon.setBackgroundColour(2 ^ 0)
  mon.setTextColour(2 ^ 15)
  mon.clear()
  mon.setCursorPos(2, 2)
  if label then mon.write(label) end
end

-- ================= The call engine =================
--
-- Five cooperating loops via parallel: the receiver never waits on the
-- camera, so a slow getFrame can no longer starve the event queue and
-- drop audio - the root cause of the old shakiness.

local function inCall(peerId, peerName, peerW, peerH)
  setState("in call")
  mic.setListening(true)
  tone("click")
  clearMonitor("Waiting for " .. peerName .. "'s video...")
  print(("Connected to %s. Hold H to hang up."):format(peerName))

  local videoW = math.min(MAX_VIDEO_W, peerW or monW)
  local videoH = math.min(MAX_VIDEO_H, peerH or monH)

  local reason = nil          -- why the call ended
  local lastHeard = os.clock()-- any message from the peer counts
  local awaitingAck = false   -- one video frame in flight at a time
  local ackDeadline = 0
  local videoSeq = 0
  local sentFrames, ackedFrames, drawnFrames = 0, 0, 0
  local audioQueue = {}       -- encoded byte-strings from the peer
  local pending = nil         -- decoded samples the speaker refused
  local started = false       -- prebuffer filled once
  local droppedChunks = 0

  local function drawFrame(f)
    for i = 1, 16 do
      mon.setPaletteColour(2 ^ (i - 1), f.palette[i])
    end
    for y = 1, f.height do
      mon.setCursorPos(1, y)
      mon.blit(f.text[y], f.fg[y], f.bg[y])
    end
  end

  -- Receive everything the peer sends; acknowledge every video frame.
  local function rxLoop()
    while true do
      local sender, message = rednet.receive(PROTOCOL, 1)
      if sender == peerId and type(message) == "table" then
        lastHeard = os.clock()
        if message.type == "audio" then
          audioQueue[#audioQueue + 1] = message.data
          if #audioQueue > MAX_BUFFER_CHUNKS then
            table.remove(audioQueue, 1)
            droppedChunks = droppedChunks + 1
          end
          os.queueEvent("vphone_audio")
        elseif message.type == "video" then
          drawFrame(message.data)
          drawnFrames = drawnFrames + 1
          send(peerId, "video_ack", { seq = message.seq })
        elseif message.type == "video_ack" then
          if message.seq == videoSeq then
            awaitingAck = false
            ackedFrames = ackedFrames + 1
          end
        elseif message.type == "hangup" then
          reason = peerName .. " hung up."
          return
        end
      end
      if os.clock() - lastHeard > LINK_LOST_AFTER then
        reason = "Link lost (nothing from " .. peerName .. " for "
          .. LINK_LOST_AFTER .. "s)."
        return
      end
    end
  end

  -- Stream the microphone out as it arrives.
  local function audioTxLoop()
    while true do
      local _, _, samples = os.pullEvent("microphone_audio")
      send(peerId, "audio", { data = encode(samples) })
    end
  end

  -- Feed the speaker from the jitter buffer. Playback starts only after a
  -- small prebuffer, and refused audio waits for speaker_audio_empty.
  local function audioRxLoop()
    while true do
      if pending then
        if speaker.playAudio(pending) then pending = nil
        else os.pullEvent("speaker_audio_empty") end
      elseif #audioQueue >= (started and 1 or PREBUFFER_CHUNKS) then
        started = true
        local batch = table.concat(audioQueue)
        audioQueue = {}
        pending = decode(batch)
      else
        os.pullEvent("vphone_audio")
      end
    end
  end

  -- Render and send one frame at a time, each verified by the peer's ack
  -- before the next renders. The feed self-paces to the real link rate.
  local function videoTxLoop()
    while true do
      if awaitingAck and os.clock() < ackDeadline then
        sleep(0.05)
      else
        local frameStart = os.clock()
        local ok, f = pcall(cam.getFrame, videoW, videoH)
        if ok and f then
          videoSeq = videoSeq + 1
          send(peerId, "video", { data = f, seq = videoSeq })
          sentFrames = sentFrames + 1
          awaitingAck = true
          ackDeadline = os.clock() + 2
        end
        local wait = 1 / maxFps - (os.clock() - frameStart)
        sleep(wait > 0 and wait or 0.05)
      end
    end
  end

  -- Keys, keepalive, and a once-a-second status line.
  local function uiLoop()
    local lastStats = os.clock()
    local lastSent, lastDrawn = 0, 0
    local keepalive = os.startTimer(2)
    local beat = os.startTimer(HEARTBEAT)
    while true do
      local event = { os.pullEvent() }
      if event[1] == "key" and event[2] == keys.h then
        send(peerId, "hangup")
        reason = "Hung up."
        return
      elseif event[1] == "timer" and event[2] == keepalive then
        send(peerId, "keepalive")
        keepalive = os.startTimer(2)
      elseif event[1] == "timer" and event[2] == beat then
        heartbeat()
        beat = os.startTimer(HEARTBEAT)
      end
      if os.clock() - lastStats >= 1 then
        local outFps = ackedFrames - lastSent
        local inFps = drawnFrames - lastDrawn
        lastSent, lastDrawn = ackedFrames, drawnFrames
        lastStats = os.clock()
        local _, line = term.getCursorPos()
        term.setCursorPos(1, line)
        term.clearLine()
        term.write(("video out %2d in %2d fps | buf %2d | drop %d   ")
          :format(outFps, inFps, #audioQueue, droppedChunks))
      end
    end
  end

  parallel.waitForAny(rxLoop, audioTxLoop, audioRxLoop, videoTxLoop, uiLoop)

  mic.setListening(false)
  print("")
  print(reason or "Call ended.")
  tone("click")
  clearMonitor("No call")
  setState("idle")
end

-- ================= Call setup =================

local function dial(name)
  if name == myName then
    printError("That's this station.")
    return
  end
  local id, peerState = resolve(name)
  if not id then
    printError(("No station called '%s' is online."):format(name))
    return
  end
  if peerState and peerState ~= "idle" then
    print(("%s is busy."):format(name))
    tone("busy")
    return
  end
  setState("dialing")
  send(id, "ring", { v = VERSION, w = monW, h = monH })
  print(("Ringing %s... (N to give up)"):format(name))
  local deadline = os.clock() + RING_TIMEOUT
  local ringback = os.startTimer(1.5)
  tone("ringback")
  while os.clock() < deadline do
    local event = { os.pullEvent() }
    if event[1] == "rednet_message" and event[2] == id
      and type(event[3]) == "table" and event[4] == PROTOCOL then
      if event[3].type == "answer" then
        if event[3].v ~= VERSION then
          printError("Their station runs a different vphone version. Update both.")
          send(id, "hangup")
          setState("idle")
          return
        end
        inCall(id, name, event[3].w, event[3].h)
        return
      elseif event[3].type == "decline" then
        print(("%s declined."):format(name))
        tone("busy")
        setState("idle")
        return
      elseif event[3].type == "busy" then
        print(("%s is busy."):format(name))
        tone("busy")
        setState("idle")
        return
      end
    elseif event[1] == "timer" and event[2] == ringback then
      tone("ringback")
      ringback = os.startTimer(1.5)
    elseif event[1] == "key" and event[2] == keys.n then
      break
    end
  end
  send(id, "hangup")
  print("No answer.")
  tone("busy")
  setState("idle")
end

local function answerFlow(sender, message)
  local caller = message.from or ("#" .. sender)
  if message.v ~= VERSION then
    send(sender, "decline")
    printError(("Missed call from %s: different vphone version. Update both.")
      :format(caller))
    return
  end
  setState("ringing")
  print(("Incoming video call from %s. Y = answer, N = decline."):format(caller))
  local deadline = os.clock() + RING_TIMEOUT
  local chime = os.startTimer(0)
  while os.clock() < deadline do
    local event = { os.pullEvent() }
    if event[1] == "key" and event[2] == keys.y then
      send(sender, "answer", { v = VERSION, w = monW, h = monH })
      inCall(sender, caller, message.w, message.h)
      return
    elseif event[1] == "key" and event[2] == keys.n then
      send(sender, "decline")
      print("Declined.")
      setState("idle")
      return
    elseif event[1] == "timer" and event[2] == chime then
      tone("ring")
      chime = os.startTimer(1.2)
    elseif event[1] == "rednet_message" and event[2] == sender
      and type(event[3]) == "table" and event[3].type == "hangup" then
      print(("%s gave up."):format(caller))
      setState("idle")
      return
    end
  end
  send(sender, "decline")
  print("Missed call from " .. caller .. ".")
  setState("idle")
end

local function listStations()
  if not exchangeId then
    print("No exchange on the network; the directory needs one.")
    return
  end
  rednet.send(exchangeId, { type = "who" }, EXCHANGE_PROTOCOL)
  local sender, message = rednet.receive(EXCHANGE_PROTOCOL, 2)
  if sender == exchangeId and type(message) == "table" and message.type == "directory" then
    if #message.stations == 0 then
      print("No stations online.")
      return
    end
    print("Stations online:")
    for _, station in ipairs(message.stations) do
      local marker = station.name == myName and " (you)" or ""
      print(("  %-14s %s%s"):format(station.name, station.state, marker))
    end
  else
    print("The exchange did not answer.")
  end
end

local ok, err = pcall(function()
  clearMonitor("No call")
  if exchangeId then
    rednet.send(exchangeId, { type = "register", name = myName, state = "idle" }, EXCHANGE_PROTOCOL)
    print(("Video station '%s' on the exchange."):format(myName))
  else
    print(("Video station '%s' (no exchange found: direct calls only)."):format(myName))
  end
  print("C = call, L = list stations, Q = quit.")

  local beat = os.startTimer(HEARTBEAT)
  while true do
    local event = { os.pullEvent() }
    if event[1] == "key" and event[2] == keys.c then
      write("Call who? ")
      local name = read()
      if name ~= "" then dial(name) end
      print("C = call, L = list stations, Q = quit.")
    elseif event[1] == "key" and event[2] == keys.l then
      listStations()
    elseif event[1] == "key" and event[2] == keys.q then
      break
    elseif event[1] == "rednet_message" and type(event[3]) == "table" and event[4] == PROTOCOL then
      if event[3].type == "ring" then
        if state == "idle" then
          answerFlow(event[2], event[3])
          print("C = call, L = list stations, Q = quit.")
        else
          send(event[2], "busy")
        end
      end
    elseif event[1] == "timer" and event[2] == beat then
      heartbeat()
      beat = os.startTimer(HEARTBEAT)
    end
  end
end)

mic.setListening(false)
rednet.unhost(PROTOCOL)
if not ok and err ~= "Terminated" then printError(err) end
print("Station closed.")
