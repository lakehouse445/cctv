-- patrol.lua - auto-panning camera on a monitor: sweeps left and right
-- across the frame like a mall camera nobody is steering.
-- Needs a camera and a monitor attached to this computer.
--
-- Usage: patrol [fps] [period] [span]
--   fps     draw rate 1-10, default 5
--   period  seconds for one full there-and-back sweep, default 10
--   span    degrees swept to each side 5-60, default 60

local args = { ... }
local fps = math.min(10, math.max(1, tonumber(args[1]) or 5))
local period = math.max(2, tonumber(args[2]) or 10)
local span = math.min(60, math.max(5, tonumber(args[3]) or 60))

local cam = peripheral.find("camera") or error("No camera attached", 0)
local mon = peripheral.find("monitor") or error("No monitor attached", 0)
if cam.isLocked() then error("Camera is locked", 0) end

mon.setTextScale(0.5)
local w, h = mon.getSize()
local startYaw = cam.getYaw()

print(("Patrolling %d deg each way, %ds sweep, %d fps. Hold Ctrl+T to stop.")
  :format(span, period, fps))

local ok, err = pcall(function()
  while true do
    -- Sine sweep: quick through the middle, easing into each end like a
    -- real pan head slowing down to reverse.
    cam.setYaw(span * math.sin(2 * math.pi * os.clock() / period))
    local f = cam.getFrame(w, h)
    for i = 1, 16 do
      mon.setPaletteColour(2 ^ (i - 1), f.palette[i])
    end
    for y = 1, f.height do
      mon.setCursorPos(1, y)
      mon.blit(f.text[y], f.fg[y], f.bg[y])
    end
    sleep(1 / fps)
  end
end)

cam.setYaw(startYaw)
mon.setBackgroundColour(colours.black)
mon.clear()
print()
if ok or err == "Terminated" then
  print("Patrol stopped; camera returned to its post.")
else
  printError(err)
end
