-- dfplay.lua - play a CC:TV mic recording at the right speed.
-- New recordings are WAV files with a DFPWM payload; the rate comes from
-- the header. Raw headerless .dfpwm files need the rate given (CC:TV mic
-- recordings were 16000 Hz, 8000 Hz for very early ones).
--
-- Usage: dfplay <file> [rate]     rate for raw files, defaults to 16000

local dfpwm = require("cc.audio.dfpwm")

local args = { ... }
local path = args[1] or error("Usage: dfplay <file> [rate]", 0)
local rate = tonumber(args[2]) or 16000

local speaker = peripheral.find("speaker") or error("No speaker attached", 0)
local file = fs.open(path, "rb") or error("No such file: " .. path, 0)
local decoder = dfpwm.make_decoder()

local first = file.read(4)
if first == "RIFF" then
  -- WAV container: take the rate from the header, skip to the data chunk.
  file.read(4)
  if file.read(8) ~= "WAVEfmt " then error("Unsupported WAV file", 0) end
  local fmtsize = ("<I4"):unpack(file.read(4))
  rate = select(3, ("<I2I2I4"):unpack(file.read(fmtsize)))
  while true do
    local chunk = file.read(4)
    if not chunk then error("Invalid WAV file", 0) end
    if chunk == "data" then
      file.read(4)
      break
    end
    file.read(("<I4"):unpack(file.read(4)))
  end
  first = nil
end
local up = math.max(1, math.floor(48000 / rate))

print(("Playing %s at %d Hz..."):format(path, rate))
while true do
  local chunk = file.read(2048)
  if first then
    chunk = first .. (chunk or "")
    first = nil
  end
  if not chunk or #chunk == 0 then break end
  local pcm = decoder(chunk)
  local out = {}
  local n = 0
  for i = 1, #pcm do
    local v = pcm[i]
    for _ = 1, up do
      n = n + 1
      out[n] = v
    end
  end
  while not speaker.playAudio(out, 2) do
    os.pullEvent("speaker_audio_empty")
  end
end
file.close()
print("Done.")
