-- mictest.lua - microphone test bench: echo live, record to WAV, play back.
-- Needs a microphone and one or two speakers attached, Simple Voice Chat
-- installed, and someone speaking within 8 blocks of the microphone.
-- With two speakers attached, live echo and playback run in stereo
-- (first speaker = left, second = right), and recordings save stereo WAVs.
--
-- Usage:
--   mictest live                      echo the microphone through the speakers
--   mictest record <name> [seconds]   save <name>.wav (default 10 seconds)
--   mictest play <name>               play <name>.wav through the speakers
--
-- WAV files land in this computer's folder on your PC, so any audio player
-- can open them: saves/<world>/computercraft/computer/<id>/<name>.wav
-- Voice chat only transmits while someone speaks; mictest fills the gaps
-- with silence so recordings stay true to the clock.

local args = { ... }
local mode = args[1]

local RATE = 48000

local function need(kind)
  return peripheral.find(kind) or error("No " .. kind .. " attached", 0)
end

local function speakers()
  local found = { peripheral.find("speaker") }
  if #found == 0 then error("No speaker attached", 0) end
  if #found >= 2 then
    print(("Stereo: %s = left, %s = right"):format(
      peripheral.getName(found[1]), peripheral.getName(found[2])))
  else
    print("One speaker: mono. Attach a second speaker for stereo.")
  end
  return found
end

local function playAll(speaker, samples)
  while not speaker.playAudio(samples) do
    os.pullEvent("speaker_audio_empty")
  end
end

if mode == "live" then
  local mic = need("microphone")
  local out = speakers()
  mic.setListening(true)
  print("Echoing the microphone. Hold Ctrl+T to stop.")
  while true do
    local _, _, mono, left, right = os.pullEvent("microphone_audio")
    if #out >= 2 then
      out[1].playAudio(left)
      out[2].playAudio(right)
    else
      out[1].playAudio(mono)
    end
  end

elseif mode == "record" then
  local name = args[2] or error("Usage: mictest record <name> [seconds]", 0)
  local seconds = math.max(1, tonumber(args[3]) or 10)
  local mic = need("microphone")

  -- Stereo: 96000 bytes per second of voice. Check the disk quota first.
  local free = fs.getFreeSpace("/") - 1024
  local maxSeconds = math.floor(free / (RATE * 2))
  if maxSeconds < 1 then
    error(("Computer disk is full (%d bytes free). Delete old recordings, or raise "
      .. "computer_space_limit in computercraft-server.toml."):format(free), 0)
  end
  if seconds > maxSeconds then
    print(("Only %ds fit on the computer's disk; trimming."):format(maxSeconds))
    seconds = maxSeconds
  end
  mic.setListening(true)

  print(("Recording %ds to %s.wav - speak near the microphone."):format(seconds, name))
  local chunks = {}
  local frames = 0
  local voiced = 0
  local start = os.clock()
  local stopAt = start + seconds
  local timer = os.startTimer(seconds)
  while os.clock() < stopAt do
    local event, a, _, left, right = os.pullEvent()
    if event == "microphone_audio" then
      local expected = math.floor((os.clock() - start) * RATE) - #left
      if expected > frames then
        chunks[#chunks + 1] = string.rep("\128\128", expected - frames)
        frames = expected
      end
      local out = {}
      for i = 1, #left do
        out[i] = string.char(left[i] + 128, right[i] + 128)
      end
      chunks[#chunks + 1] = table.concat(out)
      frames = frames + #left
      voiced = voiced + #left
    elseif event == "timer" and a == timer then
      break
    end
  end
  if frames < seconds * RATE then
    chunks[#chunks + 1] = string.rep("\128\128", seconds * RATE - frames)
    frames = seconds * RATE
  end

  local data = table.concat(chunks)
  local file = fs.open(name .. ".wav", "wb")
  file.write("RIFF")
  file.write(string.pack("<I4", 36 + #data))
  file.write("WAVEfmt ")
  file.write(string.pack("<I4I2I2I4I4I2I2", 16, 1, 2, RATE, RATE * 2, 2, 8))
  file.write("data")
  file.write(string.pack("<I4", #data))
  file.write(data)
  file.close()
  print(("Saved %s.wav: %.1fs stereo, %.1fs of it voice."):format(name, frames / RATE, voiced / RATE))
  if voiced == 0 then
    print("No voice arrived. Is Simple Voice Chat running, and was anyone talking in range?")
  end

elseif mode == "play" then
  local name = args[2] or error("Usage: mictest play <name>", 0)
  local out = speakers()
  local path = fs.exists(name .. ".wav") and (name .. ".wav") or name
  local file = fs.open(path, "rb") or error("No such file: " .. path, 0)
  local content = file.readAll()
  file.close()
  local channels = string.unpack("<I2", content, 23)
  local data = content:sub(45)
  local frames = math.floor(#data / channels)
  print(("Playing %.1fs (%d channel%s)..."):format(frames / RATE, channels, channels > 1 and "s" or ""))
  local CHUNK = 8192
  for offset = 0, frames - 1, CHUNK do
    local count = math.min(CHUNK, frames - offset)
    if channels == 2 and #out >= 2 then
      local left, right = {}, {}
      for i = 1, count do
        local at = (offset + i - 1) * 2
        left[i] = data:byte(at + 1) - 128
        right[i] = data:byte(at + 2) - 128
      end
      parallel.waitForAll(
        function() playAll(out[1], left) end,
        function() playAll(out[2], right) end)
    else
      local mixed = {}
      for i = 1, count do
        if channels == 2 then
          local at = (offset + i - 1) * 2
          mixed[i] = math.floor((data:byte(at + 1) + data:byte(at + 2)) / 2) - 128
        else
          mixed[i] = data:byte(offset + i) - 128
        end
      end
      playAll(out[1], mixed)
    end
  end
  print("Done.")

else
  print("Usage:")
  print("  mictest live")
  print("  mictest record <name> [seconds]")
  print("  mictest play <name>")
end
