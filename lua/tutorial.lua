-- tutorial.lua - record the capture card and the microphone together, in
-- the background, so the computer stays free while the tutorial rolls.
-- Video goes to the tape in the capture card; narration goes to <name>.wav
-- on this computer (DFPWM in a WAV container, ffmpeg 5.1+ reads it as-is).
--
-- Tip: with the card next to both a monitor and this computer, pick the
-- source in its GUI (or capture.setSource "computer") to film this screen.
--
-- Usage:
--   tutorial start <name> [fps]   begin both recordings in a background tab
--   tutorial stop                 finish both; tape commits, <name>.wav saves
--
-- Combining: export the tape recording as MP4 from the capture card GUI,
-- then mux the narration in:
--   ffmpeg -i export.mp4 -i <name>.wav -c:v copy -shortest tutorial.mp4
-- Both recordings start within a tick of each other, so they line up.

local STOP_FLAG = "/.tutorial_stop"
local STATE = "/.tutorial_state"

local args = { ... }
local mode = args[1]

local function running()
  return fs.exists(STATE)
end

if mode == "start" then
  local name = args[2] or error("Usage: tutorial start <name> [fps]", 0)
  local fps = math.min(20, math.max(1, tonumber(args[3]) or 5))
  if running() then error("Already recording - run 'tutorial stop' first", 0) end
  if not multishell then error("Needs an advanced computer (multishell) to record in the background", 0) end
  -- Fail here, in the foreground, where the user can read the error.
  if not peripheral.find("capture_card") then error("No capture card attached", 0) end
  if not peripheral.find("microphone") then error("No microphone attached", 0) end

  local file = fs.open(STATE, "w")
  file.writeLine(name)
  file.writeLine(tostring(fps))
  file.close()
  if fs.exists(STOP_FLAG) then fs.delete(STOP_FLAG) end

  local tab = shell.openTab(shell.getRunningProgram(), "run")
  multishell.setTitle(tab, "REC " .. name)
  print(("Recording video + mic at %d fps. The computer is yours;"):format(fps))
  print("run 'tutorial stop' to finish.")
  return
end

if mode == "stop" then
  if not running() then error("Nothing is recording", 0) end
  local file = fs.open(STOP_FLAG, "w")
  file.write("stop")
  file.close()
  print("Stopping - the recorder tab commits the tape and writes the WAV.")
  return
end

if mode ~= "run" then
  error("Usage: tutorial start <name> [fps] | tutorial stop", 0)
end

-- === The background recorder (lives in its own multishell tab) ===

local state = fs.open(STATE, "r")
local name = state.readLine()
local fps = tonumber(state.readLine()) or 5
state.close()

local capture = peripheral.find("capture_card") or error("No capture card attached", 0)
local mic = peripheral.find("microphone") or error("No microphone attached", 0)
local rate = mic.getSampleRate()

mic.setListening(true)
capture.record(fps)
local start = os.clock()

-- Mic strings are stored as-is; wall-clock gaps become 0x55 bytes (DFPWM
-- silence) so the track stays true to the video's clock. Deficits under a
-- quarter second are event-delivery jitter (busy ticks, autosaves), not
-- silence - padding those punched holes into continuous speech. Same
-- container as mictest: WAVE_FORMAT_EXTENSIBLE with the community DFPWM GUID.
local GAP = rate / 4
local chunks = {}
local frames = 0
local lowDisk = false
local poll = os.startTimer(0.25)

while true do
  local event, a, mono = os.pullEvent()
  if event == "microphone_audio" then
    local expected = math.floor((os.clock() - start) * rate) - #mono * 8
    if expected - frames > GAP then
      chunks[#chunks + 1] = string.rep("\85", math.floor((expected - frames) / 8))
      frames = expected
    end
    chunks[#chunks + 1] = mono
    frames = frames + #mono * 8
  elseif event == "timer" and a == poll then
    if fs.exists(STOP_FLAG) then break end
    if not capture.isRecording() then break end -- frame cap or tape trouble
    if fs.getFreeSpace("/") < 16 * 1024 then
      lowDisk = true
      break
    end
    poll = os.startTimer(0.25)
  end
end

if capture.isRecording() then pcall(capture.stop) end
mic.setListening(false)

local seconds = os.clock() - start
if frames < seconds * rate then
  chunks[#chunks + 1] = string.rep("\85", math.floor((seconds * rate - frames) / 8))
  frames = math.floor(seconds * rate)
end

local data = table.concat(chunks)
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

if fs.exists(STOP_FLAG) then fs.delete(STOP_FLAG) end
if fs.exists(STATE) then fs.delete(STATE) end

print(("Done: %.1fs on tape, %s.wav (%d bytes) on disk."):format(seconds, name, #data))
if lowDisk then printError("Stopped early: computer disk nearly full.") end
