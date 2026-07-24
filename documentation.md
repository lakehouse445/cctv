# CC:TV Documentation

CC:TV adds cameras, tape recording, and voice communications to ComputerCraft.
Every block is a peripheral. You can control all blocks from Lua.

This document uses ASD-STE100 Simplified Technical English.

---

## 1. Requirements

- Minecraft 1.20.1
- Forge 47 or later
- CC:Tweaked 1.119.0 or later
- Simple Voice Chat (only for the microphones). This mod is optional.

---

## 2. Terms

| Term | Meaning |
|---|---|
| Recording | The video data that a camera or a monitor makes. |
| Tape | The item that holds recordings. |
| Frame | One picture in a recording. |
| Cell | One text position on a monitor. Each cell shows 2 x 3 pixels. |
| Array | Two or more VCR blocks in a vertical stack. |

---

## 3. Blocks and items

The mod adds 6 blocks and 1 item. Craft each block as shown.
`I` = iron ingot, `N` = iron nugget, `S` = smooth stone, `R` = redstone,
`G` = glass pane, `P` = paper.

| Block or item | Recipe | Peripheral |
|---|---|---|
| Camera | `III` / `IGR` / `III` | `camera` |
| Capture Card | `SSS` / `IRI` / `SSS` | `capture_card` |
| Tape (makes 2) | `NNN` / `PRP` / `NNN` | — |
| Playback Deck | `SSS` / `IRI` / `III` | `playback_deck` |
| VCR | `III` / `SRS` / `III` | `vcr` |
| Intercom | `SNS` / `NRN` / `SNS` | `microphone` |
| Desktop Microphone | `N` / `I` / `S` | `microphone` |

To use a block from a computer, connect the block to the computer.
Use a wired modem, or put the block next to the computer.

---

## 4. Camera

The camera shows the world as a live picture. Mount the camera on a wall, a
floor, or a ceiling. The base and the arm are static. The head turns with
`setYaw` and tilts with `setPitch`. A red light comes on when a computer
reads the camera.

To aim the camera by hand, click the camera with an empty hand. A scope opens
and shows the camera picture. Drag the mouse to turn and tilt the head. Turn
the mouse wheel to zoom. Press ESC to close the scope. You cannot aim a
locked camera.

The camera makes a real picture of the world. It shows blocks with their true
textures, and entities with their true models and skins. It also shows player
skins, armor, held items, and name tags. On a dedicated server the mod
downloads the standard Minecraft assets one time from Mojang, then bakes the
models itself, so the picture keeps its full quality there.

The camera makes a black and white picture by default. A black and white
picture uses all 16 palette colors for one hue. This makes the picture more
legible. The camera sets the exposure automatically: a dark scene becomes
brighter. Use `setColorMode` to select `sepia` or full `color`.

The view distance is 128 blocks.

### 4.1 Camera functions

Peripheral type: `camera`.

| Function | Result | Notes |
|---|---|---|
| `getFrame([w, h])` | table | The picture. `w` and `h` are in cells. |
| `getYaw()` / `setYaw(deg)` | number | Turn left or right. Range -60 to 60. |
| `getPitch()` / `setPitch(deg)` | number | Turn up or down. Range -45 to 45. Up is positive. |
| `getZoom()` / `setZoom(level)` | number | Zoom the lens. Range 1 to 10. |
| `getColorMode()` / `setColorMode(mode)` | string | `bw` (default), `sepia`, or `color`. |
| `isLocked()` / `setLocked(on)` | boolean | Lock stops other users from moving the camera. |
| `getMotionThreshold()` / `setMotionThreshold(f)` | number | The change that starts a motion event. |
| `getRange()` | number | The view distance in blocks. |

The default frame size is 51 x 19 cells. The largest frame size is 162 x 81
cells.

### 4.2 The frame table

`getFrame` gives a table with these fields:

- `width` and `height` — the size in cells.
- `text`, `fg`, `bg` — one string for each row. Give these to `blit`.
- `palette` — a list of 16 colors. The camera selects the best 16 colors for
  each frame.

To show a frame on a monitor, do these steps:

1. Set the 16 palette colors with `setPaletteColour`.
2. For each row, move the cursor to the start of the row.
3. Draw the row with `blit(text, fg, bg)`.

### 4.3 The Camera Link and the Microphone Link

The Camera Link item connects a camera to a wired modem without cables. The
Microphone Link item does the same for a microphone. An unbroken run of
solid blocks must connect the two blocks.

Hold the item to see a marker in each device of that type and each wired
modem, and the path of each link. Click the device, then click a wired
modem. The device then shows on the modem's network as a peripheral. To
remove a link, sneak and click the device or the modem with the item.

One modem holds a maximum of 6 linked devices. This limit does not change
the number of wired peripherals. If the path breaks, the link searches for a
new path. After 5 searches with no path, the link ends.

### 4.4 The motion event

The camera sends a `camera_motion` event when the picture changes. The camera
sends this event only when a computer is attached.

Event fields:

1. `camera_motion` — the event name.
2. `side` — the side of the camera.
3. `changedFraction` — the part of the picture that changed. Range 0 to 1.

### 4.5 The player event

The camera sends a `camera_player` event when the set of players in the
picture changes. The camera knows the players it draws. It does not compare
pictures for this event. The camera sends this event only when a computer is
attached.

Event fields:

1. `camera_player` — the event name.
2. `side` — the side of the camera.
3. `players` — a list of the player names in the picture. The list is empty
   when the last player leaves the picture.

---

## 5. Capture Card

The capture card records the screen of a monitor. Put the capture card next to
a monitor. Put a tape in the capture card. The card records the monitor to the
tape.

The card can also make an MP4 video file on your computer. Open the card
screen and select export. You cannot export from Lua.

### 5.1 Capture card functions

Peripheral type: `capture_card`.

| Function | Result | Notes |
|---|---|---|
| `record([fps])` | — | Start a recording. Speed 1 to 20 frames each second. |
| `stop()` | — | Stop the recording. The card writes it to the tape. |
| `isRecording()` | boolean | True when the card records. |
| `getFrameCount()` | number | The number of frames in the current recording. |
| `hasTape()` | boolean | True when a tape is in the card. |
| `getTapeLabel()` | string | The name of the tape. |
| `getCapacity()` | number | The total space of the tape in bytes. |
| `getFreeSpace()` | number | The free space of the tape in bytes. |
| `list()` | table | The recordings on the tape. |
| `delete(name)` | boolean | Delete one recording. |

Each `list` entry has these fields: `name`, `bytes`, `fps`, `frames`, and
`seconds`.

---

## 6. Tapes

A tape holds recordings. One tape holds 10 MB. A tape can hold only recordings.
You cannot write other files to a tape.

All recordings have one standard size: 162 x 81 cells. The recorder scales
each captured frame to this size. Playback scales the picture to the size of
the output monitor.

To name a tape, put the tape in an anvil. Type a name. The name shows on the
tape label. A name can have a maximum of 12 characters. The anvil shows
"Too long!" for a longer name.

You must rewind a tape before you play it again. The playback deck rewinds the
tape. The tape tooltip shows the rewind position.

---

## 7. Playback Deck

The playback deck plays a tape on a monitor. Put the deck next to a monitor.
Put a tape in the deck. The deck plays the recording on the monitor.

The deck has 4 states: `empty`, `filled`, `playing`, and `rewinding`.

To play a tape by hand, right-click the deck with an empty hand. This starts or
stops the tape.

### 7.1 Playback deck functions

Peripheral type: `playback_deck`.

| Function | Result | Notes |
|---|---|---|
| `play([name])` | — | Play a recording. The deck plays the first recording by default. |
| `pause()` | — | Stop the tape but keep the position. |
| `stop()` | — | Stop the tape. |
| `rewind()` | — | Move the tape back to the start. |
| `fastForward()` | — | Move the tape forward 10 seconds. |
| `seek(seconds)` | — | Move the tape to a time. |
| `getState()` | string | The deck state. |
| `getPosition()` | number | The current time in seconds. |
| `getLength()` | number | The full time in seconds. |
| `getRecording()` | string | The name of the current recording. |
| `hasTape()` | boolean | True when a tape is in the deck. |
| `getTapeLabel()` | string | The name of the tape. |
| `list()` | table | The recordings on the tape. |
| `eject()` | — | Push the tape out of the deck. |

---

## 8. VCR and RAID arrays

A VCR holds one tape. Put VCR blocks in a vertical stack to make an array. The
array joins the tapes into one large recorder. The bottom VCR controls the
array.

Each VCR has 3 states: `empty`, `filling`, and `full`.

### 8.1 Array modes

The array has 3 modes:

| Mode | Space | Function | Result if you lose a tape |
|---|---|---|---|
| `SPAN` | The sum of all tapes | Fill one tape, then fill the next tape. | You lose that part. Playback shows "TAPE MISSING". |
| `STRIPE` | The sum of all tapes | Put frames across all tapes. | You lose the full recording. |
| `MIRROR` | Half of all tapes | Keep a full copy on each half. | The other half keeps the recording. |

`SPAN` is the default mode. Loop mode records without a stop. It records over
the oldest recording when the array is full. Start loop mode with
`record(fps, true)`.

### 8.2 VCR functions

Peripheral type: `vcr`. Some functions act on one deck. Other functions act on
the full array.

**One deck:**

| Function | Result | Notes |
|---|---|---|
| `getIndex()` | number | The position of this deck in the array. |
| `hasTape()` | boolean | True when a tape is in this deck. |
| `getTapeLabel()` | string | The name of the tape. |
| `getFreeSpace()` | number | The free space of the tape in bytes. |
| `list()` | table | The recordings on this tape. |
| `eject()` | — | Push the tape out of this deck. |

**The array:**

| Function | Result | Notes |
|---|---|---|
| `getMode()` / `setMode(name)` | string | The array mode. |
| `getDeckCount()` | number | The number of decks in the array. |
| `getDecks()` | table | The state of each deck. |
| `getCapacity()` | number | The total space of the array in bytes. |
| `getHealth()` | table | The array status. |
| `record([fps], [loop])` | — | Start a recording. Set `loop` to true for loop mode. |
| `stop()` | — | Stop the recording. |
| `isRecording()` | boolean | True when the array records. |
| `getFrameCount()` | number | The number of frames in the current recording. |
| `play(name)` | — | Play a recording on an attached monitor. |
| `stopPlayback()` | — | Stop the playback. |
| `isPlaying()` | boolean | True when the array plays. |
| `getPlaybackPosition()` | number | The current time in seconds. |
| `getPlaybackLength()` | number | The full time in seconds. |
| `listAll()` | table | All recordings on all tapes. |

`getHealth` gives a table with `status` (`OK` or `DEGRADED`), a list of `empty`
decks, and a list of `full` decks.

---

## 9. Microphones

The microphone sends real player voices to Lua. The mod has 2 microphones:

- The intercom mounts on a wall.
- The desktop microphone stands on a floor.

You need the Simple Voice Chat mod for the microphones. The microphone picks up
voices within 8 blocks. A voice becomes quieter when the player is farther
from the microphone. The mod adds a radio filter to each voice.

The microphone hears in stereo. A voice on the left side of the microphone's
front is stronger in the left channel. A centered voice is equal in the two
channels.

To turn a microphone on or off, right-click the block. You can also use
`setListening`.

### 9.1 Microphone functions

Peripheral type: `microphone`.

| Function | Result | Notes |
|---|---|---|
| `setListening(on)` | — | Turn the microphone on or off. |
| `isListening()` | boolean | True when the microphone is on. |
| `getSampleRate()` | number | The audio sample rate. This is 48000. |
| `getPickupRange()` | number | The pickup distance in blocks. This is 8. |

### 9.2 The audio event

The microphone sends a `microphone_audio` event with new audio.

Event fields:

1. `microphone_audio` — the event name.
2. `side` — the side of the microphone.
3. `samples` — a list of 8-bit audio values. This is the mono mix.
4. `left` — the left channel of the stereo stage.
5. `right` — the right channel of the stereo stage.

Give a list to `speaker.playAudio` to play the voice. For stereo, play `left`
and `right` on two speakers.

---

## 10. Example programs

### 10.1 Show a camera on a monitor

```lua
local cam = peripheral.find("camera")
local mon = peripheral.find("monitor")
mon.setTextScale(0.5)
local w, h = mon.getSize()
while true do
  local f = cam.getFrame(w, h)
  for i = 1, 16 do mon.setPaletteColour(2 ^ (i - 1), f.palette[i]) end
  for y = 1, f.height do
    mon.setCursorPos(1, y)
    mon.blit(f.text[y], f.fg[y], f.bg[y])
  end
  sleep(0.2)
end
```

### 10.2 Make a motion alarm

```lua
local cam = peripheral.find("camera")
while true do
  local _, side, amount = os.pullEvent("camera_motion")
  print(("Motion: %d%%"):format(amount * 100))
end
```

### 10.3 Record a camera to a VCR array

To record a camera, show the camera on a monitor with the program in 10.1.
Put the monitor against the VCR array. Then call `vcr.record(fps)`. The
array records the monitor. Call `vcr.record(fps, true)` for a loop recorder.
