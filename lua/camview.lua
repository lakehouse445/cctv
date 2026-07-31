-- camview.lua - live camera feed on this computer's own screen, no monitor.
-- Needs a camera attached (cable or adjacency). Advanced computers show
-- full color; standard computers get the nearest of the 16 stock shades.
--
-- Usage: camview [fps]
--   fps  1-10, default 5

local args = { ... }
local fps = math.min(10, math.max(1, tonumber(args[1]) or 5))
local cam = peripheral.find("camera") or error("No camera attached", 0)

local w, h = term.getSize()
term.clear()

local ok, err = pcall(function()
  while true do
    local f = cam.getFrame(w, h)
    for i = 1, 16 do
      term.setPaletteColour(2 ^ (i - 1), f.palette[i])
    end
    for y = 1, f.height do
      term.setCursorPos(1, y)
      term.blit(f.text[y], f.fg[y], f.bg[y])
    end
    sleep(1 / fps)
  end
end)

-- Hand the terminal back the way the shell expects it.
for i = 0, 15 do
  term.setPaletteColour(2 ^ i, term.nativePaletteColour(2 ^ i))
end
term.setBackgroundColour(colours.black)
term.setTextColour(colours.white)
term.clear()
term.setCursorPos(1, 1)
if not (ok or err == "Terminated") then printError(err) end
