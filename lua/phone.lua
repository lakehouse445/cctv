-- phone.lua - a long-distance telephone network over rednet.
-- Voice rides the microphone's radio filter, so calls sound like a real
-- line. Speakers are inaudible to voice chat mics: no feedback, ever.
--
-- The exchange is a plain computer with a modem that routes call setup and
-- keeps the directory; audio always flows directly between stations, so the
-- exchange never carries voice load. Stations work without an exchange too
-- (direct rednet lookup), but lose the directory and busy signals.
--
-- Exchange:  a computer + modem (ender modem for unlimited range)
--   phone server
-- Station:   computer + microphone + speaker + modem
--   phone <yourname>
--
-- Station keys: C = call someone, L = list stations, Q = quit.
-- While ringing: Y = answer, N = decline. In a call: hold H to hang up.

local args = { ... }
local mode = args[1] or error("Usage: phone server | phone <yourname>", 0)

local PROTOCOL = "cctv_phone"
local EXCHANGE_PROTOCOL = "cctv_phone_x"
local RING_TIMEOUT = 20
local HEARTBEAT = 5
local PRESENCE_EXPIRY = 15

-- Prefer the ender/wireless modem: a station usually also has a wired
-- modem for its own peripherals, and rednet must not end up on that one.
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

  print("Phone exchange running. Hold Ctrl+T to stop.")
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

local function send(id, kind, data)
  rednet.send(id, { type = kind, from = myName, data = data }, PROTOCOL)
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

-- One connected call: stream mic out, play peer audio in.
local function inCall(peerId, peerName)
  setState("in call")
  mic.setListening(true)
  tone("click")
  print(("Connected to %s. Hold H to hang up."):format(peerName))
  local beat = os.startTimer(HEARTBEAT)
  while true do
    local event = { os.pullEvent() }
    if event[1] == "microphone_audio" then
      send(peerId, "audio", encode(event[3]))
    elseif event[1] == "rednet_message" and event[2] == peerId
      and type(event[3]) == "table" and event[4] == PROTOCOL then
      if event[3].type == "audio" then
        speaker.playAudio(decode(event[3].data)) -- drop when full: stay live
      elseif event[3].type == "hangup" then
        print(("%s hung up."):format(peerName))
        tone("click")
        break
      end
    elseif event[1] == "key" and event[2] == keys.h then
      send(peerId, "hangup")
      print("Hung up.")
      tone("click")
      break
    elseif event[1] == "timer" and event[2] == beat then
      heartbeat()
      beat = os.startTimer(HEARTBEAT)
    end
  end
  mic.setListening(false)
  setState("idle")
end

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
  send(id, "ring")
  print(("Ringing %s... (N to give up)"):format(name))
  local deadline = os.clock() + RING_TIMEOUT
  local ringback = os.startTimer(1.5)
  tone("ringback")
  while os.clock() < deadline do
    local event = { os.pullEvent() }
    if event[1] == "rednet_message" and event[2] == id
      and type(event[3]) == "table" and event[4] == PROTOCOL then
      if event[3].type == "answer" then
        inCall(id, name)
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

local function answerFlow(sender, caller)
  setState("ringing")
  print(("Incoming call from %s. Y = answer, N = decline."):format(caller))
  local deadline = os.clock() + RING_TIMEOUT
  local chime = os.startTimer(0)
  while os.clock() < deadline do
    local event = { os.pullEvent() }
    if event[1] == "key" and event[2] == keys.y then
      send(sender, "answer")
      inCall(sender, caller)
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
  if exchangeId then
    rednet.send(exchangeId, { type = "register", name = myName, state = "idle" }, EXCHANGE_PROTOCOL)
    print(("Station '%s' on the exchange."):format(myName))
  else
    print(("Station '%s' (no exchange found: direct calls only)."):format(myName))
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
          answerFlow(event[2], event[3].from or ("#" .. event[2]))
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
