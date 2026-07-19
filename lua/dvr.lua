-- dvr.lua - live camera wall and VCR recording in one.
-- Draws the camera feed onto the monitor while the VCR array records that
-- same monitor, so one screen is both the live view and the recording source.
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

mon.setTextScale(0.5)
local w, h = mon.getSize()

vcr.record(fps, loop)
print(("Recording at %d fps%s. Hold Ctrl+T to stop."):format(fps, loop and " (loop)" or ""))

local ok, err = pcall(function()
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
end)

if vcr.isRecording() then vcr.stop() end
print()
if ok or err == "Terminated" then
  print("Stopped. Recording committed to the array.")
else
  printError(err)
end
