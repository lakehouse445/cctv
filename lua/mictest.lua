-- mictest.lua - microphone test bench: echo live, record to DFPWM, play back.
-- Needs a microphone and one or two speakers attached, Simple Voice Chat
-- installed, and someone speaking within 8 blocks of the microphone.
-- With two speakers attached, live echo runs in stereo (first speaker =
-- left, second = right); recordings keep the mono mix.
--
-- The microphone delivers 16 kHz DFPWM strings, unpacked with the
-- cc.audio.dfpwm decoder (one decoder per stream - it is stateful). The
-- speaker wants 48 kHz, so samples are repeated 3x on the way out.
-- Recordings are the raw mic strings in a WAV container with the standard
-- DFPWM format tag: one bit per sample, 2000 bytes per second, and the
-- sample rate rides in the header so tools do not have to guess it
-- (ffmpeg 5.1+ reads these directly). Voice chat only transmits while
-- someone speaks; gaps are padded with 0x55 bytes (DFPWM silence) so
-- recordings stay true to the clock. Recordings from older versions
-- (raw .dfpwm and 8-bit PCM WAVs) still play.
--
-- Usage:
--   mictest live                      echo the microphone through the speakers
--   mictest record <name> [seconds]   save <name>.wav (default 10 seconds)
--   mictest play <name>               play <name>.wav through the speakers

local dfpwm = require("cc.audio.dfpwm")

local args = { ... }
local mode = args[1]

local SPEAKER_RATE = 48000
-- Speaker amplification (0-3, speaker default is 1): the radio-filtered
-- voice peaks well under full scale, so run the speakers hot.
local VOLUME = 2

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

local function upsample(samples, factor)
  if factor <= 1 then return samples end
  local out = {}
  local n = 0
  for i = 1, #samples do
    local v = samples[i]
    for _ = 1, factor do
      n = n + 1
      out[n] = v
    end
  end
  return out
end

local function playAll(speaker, samples)
  while not speaker.playAudio(samples, VOLUME) do
    os.pullEvent("speaker_audio_empty")
  end
end

if mode == "live" then
  local mic = need("microphone")
  local out = speakers()
  local up = math.floor(SPEAKER_RATE / mic.getSampleRate())
  local decodeMono = dfpwm.make_decoder()
  local decodeLeft = dfpwm.make_decoder()
  local decodeRight = dfpwm.make_decoder()
  mic.setListening(true)
  print("Echoing the microphone. Hold Ctrl+T to stop.")
  -- The speaker buffers one playAudio call at a time and returns false while
  -- busy, so chunks queue here and flush on every event (each rejection is
  -- followed by a speaker_audio_empty that wakes the loop for a retry).
  local stereo = #out >= 2
  local queues = stereo and { {}, {} } or { {} }
  local chunks, played, dropped, peak = 0, 0, 0, 0
  local report = os.startTimer(5)

  local function track(samples)
    for i = 1, #samples do
      local v = math.abs(samples[i])
      if v > peak then peak = v end
    end
    return samples
  end
  local function flush()
    for s = 1, #queues do
      local q = queues[s]
      while q[1] and out[s].playAudio(q[1], VOLUME) do
        table.remove(q, 1)
        played = played + 1
      end
      -- Stay live: shed backlog beyond ~0.5s instead of falling behind.
      while #q > 5 do
        table.remove(q, 1)
        dropped = dropped + 1
      end
    end
  end

  while true do
    local e, a, mono, left, right = os.pullEvent()
    if e == "microphone_audio" then
      chunks = chunks + 1
      if stereo then
        queues[1][#queues[1] + 1] = track(upsample(decodeLeft(left), up))
        queues[2][#queues[2] + 1] = track(upsample(decodeRight(right), up))
      else
        queues[1][#queues[1] + 1] = track(upsample(decodeMono(mono), up))
      end
    end
    flush()
    if e == "timer" and a == report then
      if chunks == 0 then
        print("No audio in 5s. Is SVC transmitting? Check the server log for 'Microphone pipeline'.")
      else
        print(("5s: %d chunks in, %d played, %d dropped, peak %d."):format(chunks, played, dropped, peak))
      end
      chunks, played, dropped, peak = 0, 0, 0, 0
      report = os.startTimer(5)
    end
  end

elseif mode == "record" then
  local name = args[2] or error("Usage: mictest record <name> [seconds]", 0)
  local seconds = math.max(1, tonumber(args[3]) or 10)
  local mic = need("microphone")
  local rate = mic.getSampleRate()

  -- Mono DFPWM: one bit per sample. Check the disk quota first.
  local free = fs.getFreeSpace("/") - 1024
  local maxSeconds = math.floor(free / (rate / 8))
  if maxSeconds < 1 then
    error(("Computer disk is full (%d bytes free). Delete old recordings, or raise "
      .. "computer_space_limit in computercraft-server.toml."):format(free), 0)
  end
  if seconds > maxSeconds then
    print(("Only %ds fit on the computer's disk; trimming."):format(maxSeconds))
    seconds = maxSeconds
  end
  mic.setListening(true)

  print(("Recording %ds to %s.dfpwm - speak near the microphone."):format(seconds, name))
  -- The mic strings are stored as-is; gaps become 0x55 bytes, which decode
  -- to silence and let the decoder's predictor recover within a few samples.
  local chunks = {}
  local frames = 0
  local voiced = 0
  -- Deficits under a quarter second are event-delivery jitter, not silence;
  -- padding those punched holes into continuous speech.
  local GAP = rate / 4
  local start = os.clock()
  local stopAt = start + seconds
  local timer = os.startTimer(seconds)
  while os.clock() < stopAt do
    local event, a, mono = os.pullEvent()
    if event == "microphone_audio" then
      local expected = math.floor((os.clock() - start) * rate) - #mono * 8
      if expected - frames > GAP then
        chunks[#chunks + 1] = string.rep("\85", math.floor((expected - frames) / 8))
        frames = expected
      end
      chunks[#chunks + 1] = mono
      frames = frames + #mono * 8
      voiced = voiced + #mono * 8
    elseif event == "timer" and a == timer then
      break
    end
  end
  if frames < seconds * rate then
    chunks[#chunks + 1] = string.rep("\85", math.floor((seconds * rate - frames) / 8))
    frames = seconds * rate
  end

  local data = table.concat(chunks)
  -- WAVE_FORMAT_EXTENSIBLE with the community DFPWM GUID: the headered
  -- container ffmpeg (5.1+) and the stock speaker program both understand,
  -- so the sample rate travels with the file instead of being guessed.
  local GUID = "\58\193\250\56\129\29\67\97\164\13\206\83\202\96\124\209"
  local fmt = string.pack("<I2I2I4I4I2I2I2I2I4", 0xFFFE, 1, rate, rate, 1, 1, 22, 1, 4) .. GUID
  local file = fs.open(name .. ".wav", "wb")
  file.write("RIFF")
  file.write(string.pack("<I4", 4 + 8 + #fmt + 8 + 4 + 8 + #data))
  file.write("WAVEfmt ")
  file.write(string.pack("<I4", #fmt))
  file.write(fmt)
  file.write("fact")
  file.write(string.pack("<I4I4", 4, #data * 8))
  file.write("data")
  file.write(string.pack("<I4", #data))
  file.write(data)
  file.close()
  print(("Saved %s.wav: %.1fs, %.1fs of it voice, %d bytes (DFPWM in WAV)."):format(
    name, frames / rate, voiced / rate, #data))
  if voiced == 0 then
    print("No voice arrived. Is Simple Voice Chat running, and was anyone talking in range?")
  end

elseif mode == "play" then
  local name = args[2] or error("Usage: mictest play <name>", 0)
  local out = speakers()
  local path
  if fs.exists(name .. ".wav") then path = name .. ".wav"
  elseif fs.exists(name .. ".dfpwm") then path = name .. ".dfpwm"
  else path = name end
  local file = fs.open(path, "rb") or error("No such file: " .. path, 0)
  local head = file.read(4)

  if head == "RIFF" then
    file.read(4)
    if file.read(8) ~= "WAVEfmt " then
      file.close()
      error("Unsupported WAV file", 0)
    end
    local fmtsize = ("<I4"):unpack(file.read(4))
    local fmt = file.read(fmtsize)
    local format, channels, rate, _, _, bits = ("<I2I2I4I4I2I2"):unpack(fmt)
    -- Skip any extra chunks (fact, LIST) until the audio data starts.
    while true do
      local chunk = file.read(4)
      if not chunk then
        file.close()
        error("Invalid WAV file", 0)
      end
      if chunk == "data" then
        file.read(4)
        break
      end
      local skip = ("<I4"):unpack(file.read(4))
      file.read(skip)
    end
    local up = math.max(1, math.floor(SPEAKER_RATE / rate))

    if format == 0xFFFE and bits == 1 then
      print(("Playing %s (%d Hz DFPWM)..."):format(path, rate))
      local decoder = dfpwm.make_decoder()
      -- 2048 bytes -> 16384 samples -> 49152 at the speaker rate, well
      -- under the speaker's 128k sample limit.
      while true do
        local chunk = file.read(2048)
        if not chunk then break end
        playAll(out[1], upsample(decoder(chunk), up))
      end
    elseif format == 1 and bits == 8 then
      -- Legacy PCM recordings from before the DFPWM container.
      print(("Playing %s (%d Hz PCM, %d channel%s)..."):format(
        path, rate, channels, channels > 1 and "s" or ""))
      while true do
        local chunk = file.read(8192)
        if not chunk then break end
        local mixed = {}
        local n = 0
        for i = 1, #chunk - channels + 1, channels do
          local v = chunk:byte(i)
          if channels == 2 then v = math.floor((v + chunk:byte(i + 1)) / 2) end
          n = n + 1
          mixed[n] = v - 128
        end
        playAll(out[1], upsample(mixed, up))
      end
    else
      file.close()
      error("Unsupported WAV file", 0)
    end
  else
    -- Raw headerless DFPWM from before the WAV container; assume the mic rate.
    local rate = 16000
    print(("Playing %s (assuming %d Hz raw DFPWM)..."):format(path, rate))
    local decoder = dfpwm.make_decoder()
    local up = SPEAKER_RATE / rate
    local first = head
    while true do
      local chunk = file.read(2048)
      if first then
        chunk = first .. (chunk or "")
        first = nil
      end
      if not chunk or #chunk == 0 then break end
      playAll(out[1], upsample(decoder(chunk), up))
    end
  end
  file.close()
  print("Done.")

else
  print("Usage:")
  print("  mictest live")
  print("  mictest record <name> [seconds]")
  print("  mictest play <name>")
end
