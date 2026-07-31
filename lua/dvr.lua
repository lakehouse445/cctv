-- dvr.lua - live camera wall and VCR recording in one.
-- Draws the camera feed onto the monitor while the VCR array records that
-- same monitor, so one screen is both the live view and the recording source.
-- The deck's front panel becomes a marquee: what the array is doing scrolls
-- across the 12-cell display (REC time, mode, free space, tape health).
-- The monitor must touch the VCR stack; the computer needs the camera,
-- monitor and any VCR in the stack attached (cables or adjacency).
--
-- Usage: dvr [fps] [loop]
--   fps   1-10, default 5
--   loop  pass the word "loop" to tape over the oldest footage forever

local args = { ... }
local fps = math.min(10, math.max(1, tonumber(args[1]) or 5))
local loop = args[2] == "loop"

local cam = peripheral.find("camera") or error("No camera attached", 0)
local mon = peripheral.find("monitor") or error("No monitor attached", 0)
local vcr = peripheral.find("vcr") or error("No VCR attached", 0)

local CELLS = 12
local STEP = 0.35 -- seconds per marquee step

mon.setTextScale(0.5)
local w, h = mon.getSize()

vcr.record(fps, loop)
print(("Recording at %d fps%s. Hold Ctrl+T to stop."):format(fps, loop and " (loop)" or ""))

-- The display font has no '-' or '+'; strip anything it cannot draw.
local function sanitize(text)
  return text:gsub("[^%w !\"%%',./:;?_]", " ")
end

local function status()
  local parts = {}
  if vcr.isRecording() then
    local secs = math.floor(vcr.getFrameCount() / fps)
    parts[#parts + 1] = ("REC %d:%02d"):format(math.floor(secs / 60), secs % 60)
    if loop then parts[#parts + 1] = "LOOP" end
  else
    parts[#parts + 1] = "IDLE"
  end
  parts[#parts + 1] = vcr.getMode()
  parts[#parts + 1] = ("%.1fMB FREE"):format(vcr.getCapacity() / 1048576)
  local health = vcr.getHealth()
  if health.status ~= "OK" then parts[#parts + 1] = "CHECK TAPES" end
  return sanitize(table.concat(parts, "  "))
end

-- Scrolls the status across the front panel; short text just sits there.
-- One full pass per status refresh, so the text never jumps mid-scroll.
local function marquee()
  while true do
    local text = status()
    if #text <= CELLS then
      vcr.setDisplay(text)
      sleep(1)
    else
      local track = text .. "   "
      for i = 1, #track do
        vcr.setDisplay((track .. track):sub(i, i + CELLS - 1))
        sleep(STEP)
      end
    end
  end
end

local function cameraWall()
  local lastStatus = 0
  while true do
    local f = cam.getFrame(w, h)
    for i = 1, 16 do
      mon.setPaletteColour(2 ^ (i - 1), f.palette[i])
    end
    for y = 1, f.height do
      mon.setCursorPos(1, y)
      mon.blit(f.text[y], f.fg[y], f.bg[y])
    end
    if os.clock() - lastStatus >= 1 then
      lastStatus = os.clock()
      local _, line = term.getCursorPos()
      term.setCursorPos(1, line)
      term.write(("Frames on tape: %d   "):format(vcr.getFrameCount()))
    end
    sleep(1 / fps)
  end
end

local ok, err = pcall(parallel.waitForAny, cameraWall, marquee)

if vcr.isRecording() then vcr.stop() end
pcall(vcr.setDisplay) -- hand the panel back to the automatic readout
print()
if ok or err == "Terminated" then
  print("Stopped. Recording committed to the array.")
else
  printError(err)
end
