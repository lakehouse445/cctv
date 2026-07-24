-- sentry.lua - security DVR: rolling loop buffer plus player-triggered archive.
-- Shows the camera on the monitor. The VCR array loop-records that monitor as
-- a rolling buffer that tapes over itself forever, and when a player walks
-- into the picture the capture card records the same monitor to its own tape
-- until the picture has been clear for a few seconds. Only the important
-- footage lands on the archive tape.
--
-- Setup: one monitor touching both the VCR stack and the capture card;
-- camera, monitor, VCR and capture card attached to this computer.
-- Usage: sentry [fps]
--   fps   1-10, default 5

local args = { ... }
local fps = math.min(10, math.max(1, tonumber(args[1]) or 5))
local TAIL_SECONDS = 5

local cam = peripheral.find("camera") or error("No camera attached", 0)
local mon = peripheral.find("monitor") or error("No monitor attached", 0)
local vcr = peripheral.find("vcr") or error("No VCR attached", 0)
local card = peripheral.find("capture_card") or error("No capture card attached", 0)
if not card.hasTape() then error("No tape in the capture card", 0) end

mon.setTextScale(0.5)
local w, h = mon.getSize()

vcr.record(fps, true)
print(("Buffer rolling at %d fps. Hold Ctrl+T to stop."):format(fps))

local present = false
local clearedAt = nil
local watching = {}

local function draw()
  while true do
    local f = cam.getFrame(w, h)
    for i = 1, 16 do
      mon.setPaletteColour(2 ^ (i - 1), f.palette[i])
    end
    for y = 1, f.height do
      mon.setCursorPos(1, y)
      mon.blit(f.text[y], f.fg[y], f.bg[y])
    end
    if card.isRecording() and not present and clearedAt
      and os.clock() - clearedAt > TAIL_SECONDS then
      card.stop()
      print("Archived sighting: " .. table.concat(watching, ", "))
      watching = {}
    end
    sleep(1 / fps)
  end
end

local function detect()
  while true do
    local _, _, players = os.pullEvent("camera_player")
    if #players > 0 then
      present = true
      for _, name in ipairs(players) do
        local known = false
        for _, seen in ipairs(watching) do
          known = known or seen == name
        end
        if not known then watching[#watching + 1] = name end
      end
      if not card.isRecording() then
        card.record(fps)
        print("Player in view: " .. table.concat(players, ", "))
      end
    else
      present = false
      clearedAt = os.clock()
    end
  end
end

local ok, err = pcall(parallel.waitForAny, draw, detect)

if card.isRecording() then card.stop() end
if vcr.isRecording() then vcr.stop() end
print()
if ok or err == "Terminated" then
  print("Stopped. Buffer and archive committed.")
else
  printError(err)
end
