-- camwall.lua - every camera on the network, tiled on one monitor.
-- Finds all attached cameras (cabled or camera-linked), splits the monitor
-- into a grid and draws each feed into its own cell with a name tag.
-- The monitor holds one fixed 16-step gray ramp; each frame's own palette
-- remaps onto it by brightness, so any number of cameras share the screen.
--
-- Usage: camwall [fps]
--   fps  1-20, default 20

local args = { ... }
local fps = math.min(20, math.max(1, tonumber(args[1]) or 20))

local mon = peripheral.find("monitor") or error("No monitor attached", 0)
mon.setTextScale(0.5)
local w, h = mon.getSize()

local HEX = "0123456789abcdef"

-- Fixed ramp: blit digit d shows gray d*17, "0" black through "f" white.
for i = 0, 15 do
  mon.setPaletteColour(2 ^ i, i * 17 * 0x10101)
end

local function clear()
  mon.setBackgroundColour(2 ^ 0)
  mon.setTextColour(2 ^ 15)
  mon.clear()
end

local function findCameras()
  local cams = {}
  for _, name in ipairs(peripheral.getNames()) do
    if peripheral.getType(name) == "camera" then
      cams[#cams + 1] = { name = name, cam = peripheral.wrap(name) }
    end
  end
  table.sort(cams, function(a, b) return a.name < b.name end)
  return cams
end

-- Remap one frame's palette digits onto the gray ramp by brightness.
local function rampMap(palette)
  local map = {}
  for i = 1, 16 do
    local rgb = palette[i] or 0
    local r = math.floor(rgb / 65536) % 256
    local g = math.floor(rgb / 256) % 256
    local b = rgb % 256
    local luma = (r * 299 + g * 587 + b * 114) / 1000
    local slot = math.max(0, math.min(15, math.floor(luma / 16)))
    map[HEX:sub(i, i)] = HEX:sub(slot + 1, slot + 1)
  end
  return map
end

local function drawLabel(x, y, width, text)
  mon.setCursorPos(x, y)
  mon.setTextColour(2 ^ 15)
  mon.setBackgroundColour(2 ^ 0)
  mon.write(text:sub(1, width))
end

local function drawTile(entry, f, x0, y0, tw, th)
  if f then
    local map = rampMap(f.palette)
    for y = 1, f.height do
      local fg = f.fg[y]:gsub(".", map)
      local bg = f.bg[y]:gsub(".", map)
      mon.setCursorPos(x0, y0 + y - 1)
      mon.blit(f.text[y], fg, bg)
    end
  else
    mon.setBackgroundColour(2 ^ 0)
    for y = 0, th - 1 do
      mon.setCursorPos(x0, y0 + y)
      mon.write((" "):rep(tw))
    end
    drawLabel(x0 + math.max(0, math.floor((tw - 9) / 2)), y0 + math.floor(th / 2), tw, "NO SIGNAL")
  end
  drawLabel(x0, y0 + th - 1, tw, entry.name)
end

local cams = findCameras()
if #cams == 0 then error("No cameras on the network", 0) end
clear()

local lastScan = os.clock()
while true do
  if os.clock() - lastScan >= 5 then
    lastScan = os.clock()
    local fresh = findCameras()
    if #fresh ~= #cams then
      cams = fresh
      clear()
      if #cams == 0 then error("All cameras lost", 0) end
    end
  end

  local cols = math.ceil(math.sqrt(#cams))
  local rows = math.ceil(#cams / cols)
  local tw = math.floor(w / cols)
  local th = math.floor(h / rows)

  -- Fetch every camera at once: each getFrame resolves on a server tick,
  -- so serial requests would cap the wall at 20/N fps.
  local started = os.clock()
  local frames = {}
  local tasks = {}
  for i, entry in ipairs(cams) do
    tasks[i] = function()
      local ok, f = pcall(entry.cam.getFrame, tw, th)
      if ok then frames[i] = f end
    end
  end
  parallel.waitForAll(table.unpack(tasks))

  for i, entry in ipairs(cams) do
    local c = (i - 1) % cols
    local r = math.floor((i - 1) / cols)
    drawTile(entry, frames[i], c * tw + 1, r * th + 1, tw, th)
  end

  local wait = 1 / fps - (os.clock() - started)
  sleep(wait > 0 and wait or 0)
end
