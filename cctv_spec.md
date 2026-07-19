# CC:TV — Design Spec

**Cameras, tape recording, and communications for ComputerCraft.**
A Forge 1.20.1 addon mod for CC:Tweaked.

---

## 1. What is CC:TV?

CC:TV lets ComputerCraft computers see and hear the world. It adds security cameras you can view on monitors, a way to record what's on a computer screen and save it as a real video file on your PC, physical tapes that footage is stored on, stackable VCRs for building large recording setups, and microphones that carry real player voices through in-game speakers.

The design follows a few simple ideas:

**It should feel like old surveillance gear.** Chunky VCRs, cassette-style tapes, slightly grainy footage, blinking lights. The low resolution isn't a compromise — it's the look.

**Footage is a physical thing.** Recordings live on tapes. You can carry a tape across the map, hand it to someone, lock it in a vault, steal it, or destroy it. Getting footage from one place to another means physically moving it.

**Everything is scriptable.** Every block works through the normal ComputerCraft peripheral system, so players can build their own security systems, alarm setups, and playback tools in Lua. There are also simple built-in screens for players who don't program.

**It's safe to run on a server.** Tapes can only hold camera and screen recordings — nothing else — so generous storage sizes don't let anyone abuse the server's disk. Admins get controls for everything.

---

## 2. Cameras

The camera is a small block you mount on a wall or pole. It has an obvious lens direction and a little red light when it's recording.

### Watching a feed live
Point a monitor at a camera feed and you see through the camera in real time, at full visual quality. This is the classic "security office with a wall of monitors" setup and it's cheap for the server to run, because each player's own game does the drawing.

### Letting computers see the footage
Live viewing alone doesn't let a computer *do* anything with the image. So cameras can also produce actual picture data that Lua programs can read, at up to 320x200 pixels — which is the most a maxed-out monitor can physically display anyway. These frames are a real raycast of the world: actual block models and textures, biome tints, see-through glass, and entities rendered with their real models and skins, with distance fading into the horizon. (On dedicated servers, where client textures don't exist, frames fall back to a map-color style.) Squeezed into 16 colors and dithered, it looks like good security footage.

Because programs can read frames, they can also compare them. If enough of the image changes between frames, the camera fires a `camera_motion` event — so a few lines of Lua gets you motion alarms, automatic recording, or a message to whoever's on duty.

### Controlling the camera
Cameras can pan, tilt, and zoom from Lua:

```lua
cam.setYaw(deg)    cam.getYaw()
cam.setPitch(deg)  cam.getPitch()
cam.setZoom(level) cam.getZoom()
cam.setLocked(true)  -- stop others from moving it
cam.getFrame()       -- returns the current picture for programs
```

---

## 3. Recording computer screens (the Capture Card)

The capture card records what's shown on a computer or monitor and exports it as a video file — a GIF at first, MP4 later — saved straight into a folder on your own PC, the same way screenshots work. You get a clickable message in chat when the file is ready.

This works well because a ComputerCraft screen isn't really an image — it's a grid of text characters and colors. Recording it just means noting which characters changed, which takes almost no space. Even long recordings are tiny.

A GUI on the block lets you pick what to record, the frame rate, and when to export. Whole screens record automatically; if a program wants to record just one of its own windows, there's a small Lua library for that.

---

## 4. Tapes

All footage is stored on tapes. A tape is a craftable item that holds **10 MB** of recordings, which sounds small but goes a long way — see section 8 for real numbers. You can rename a tape on an anvil and the name shows on its label, so an evidence shelf can actually read "Front Door — March" in your own handwriting, so to speak.

Tapes slot into capture cards and VCRs. Importantly, a tape can *only* store recordings made by this mod. There's no way to write your own files onto one, which is what makes it safe for servers to allow big tapes: the storage can't be misused for anything else.

The basic tape API:

```lua
local deck = peripheral.wrap("left")
deck.record("monitor_3", { fps = 5 })
deck.stop()
deck.list()            -- recordings on the inserted tape
deck.getFreeSpace()
deck.delete("rec_001")
deck.export("rec_001") -- saves a video file to your PC
```

There's also a loop mode: `record(src, { loop = true })` keeps recording forever by taping over the oldest footage first, just like a real security DVR. A looping camera never stops because its tape filled up.

---

## 5. VCRs and RAID arrays

A single tape is fine for one camera. For a serious security room, VCR decks stack into a tower, topped with a controller block, and all their tapes pool together into one big recorder. Ten decks with ten tapes gives you a 100 MB system.

Each deck shows its tape through a front slot, has a deck number on a small display, and an activity light that blinks when that specific deck is being written to.

The array can run in different modes, and the differences matter:

| Mode | Total space | What it does | If a tape is lost |
|---|---|---|---|
| **Spanning** (default) | Everything added up | Fills one tape, then spills onto the next | That tape's footage is gone; playback shows "TAPE MISSING" over the gap |
| **Striping** | Everything added up | Spreads frames across all decks; each deck can handle one recording at a time, so more decks means more cameras recording at once | One lost tape ruins every recording in the array |
| **Mirroring** | Half | Keeps a full copy on each half of the array | Footage survives — destroy one side and the other still has everything |

Tapes remember which array they came from. Take a mirrored tape to a playback deck and it plays fine, because it's a complete copy. A spanned tape plays only its own portion. A striped tape on its own plays garbage. So when someone steals a tape, what they actually got depends on how the victim set up their recorder — which makes for good stories on a roleplay server.

The array reports its own health, so you can script alarms:

```lua
raid.getHealth()   -- {status="DEGRADED", missing={3}}
raid.getMode()     raid.setMode("MIRROR")
raid.getCapacity() raid.streams()
raid.record("cam_lobby", { fps = 2, loop = true })
```

A mirrored array in loop mode is the gold standard: a recorder that maintains itself forever and survives sabotage.

---

## 6. The Playback Deck

A standalone deck that plays any tape onto an attached monitor. Carry a tape from the security office to a courtroom, slot it in, and show the footage on the big screen. Planned for a later version.

---

## 7. Microphones and voice

If the server runs the Simple Voice Chat mod, CC:TV microphones can pick up real player voices near the block and pass them to Lua as audio data, which programs can then play through any ComputerCraft speaker anywhere. That's working telephones, intercoms, PA systems, and — this being a surveillance mod — bugs and wiretaps.

Voices coming through the system are deliberately given a **radio-style filter**: slightly thin, a little fuzzy, with soft crackle underneath — like a two-way radio or an old intercom. Technically this means trimming the audio down to telephone-like frequencies and mixing in faint static before it's converted to ComputerCraft's speaker format (which is already pleasantly crunchy on its own — the filter leans into it rather than fighting it). The result: nobody sounds like they're standing next to you. They sound like they're *on the line*. The strength of the effect is a config option, from subtle to full dispatcher-radio.

There's a natural short delay of a fraction of a second, which honestly makes it feel more like real intercom hardware, not less.

Voice chat is an optional dependency — the rest of the mod works fine without it.

---

## 8. How footage is stored (and why tapes last so long)

You don't need to know any of this to use the mod, but it explains the numbers.

Footage is saved as 16-color images, compressed, and — crucially — between full snapshots the tape only stores *what changed*. A camera watching an empty hallway costs almost nothing per second, no matter how detailed the hallway is. Cost goes up when things move, and only in proportion to how much moves. Idle cameras also automatically drop to a very slow frame rate and speed up when motion is detected, like a real security DVR.

About those 16 colors: monitors can only show 16 colors at once, but on advanced monitors you can choose *which* 16. So each recording picks the best 16 colors for what it's actually looking at — a desert scene gets sand tones, a night scene gets grays and blues — which improves the picture dramatically for the cost of a few extra bytes. (One caveat: a monitor has one palette at a time, so several feeds squeezed onto a single monitor share colors. A bank of separate monitors — the classic camera wall — has no such limit, and looks better anyway.)

Footage can also use a fine crosshatch pattern to fake in-between shades, which makes gradients like skies and shadows look much smoother. The pattern is fixed in place, so it doesn't add cost to the "only store what changed" trick. Exported video files get an even nicer smoothing pass, since file size on your PC doesn't matter.

Tested against real screenshots — including a busy aerial shot of an entire city center, which is about the hardest thing a camera could look at — the worst case works out to roughly 2 KB per second of continuous footage. That means one 10 MB tape holds:

- about **1.5 hours** of the busiest footage imaginable,
- **several hours** of ordinary activity,
- and far more for a typical security camera that spends most of its time watching nothing happen.

A grayscale mode is also available in config, for servers that want proper black-and-white security footage.

---

## 9. Privacy and server safety

- Active cameras show a small blinking red light. Servers can force this on.
- Camera placement respects land claims (config hook for protection plugins).
- Exporting footage requires access to the recording block, so protection plugins govern who can pull tapes and files.
- Admins can cap total recording storage per player, no matter how many tapes or arrays they build.
- The server work spent drawing camera frames has a per-world budget, so cameras can't lag the server.

---

## 10. Art & assets

Everything should look like it belongs next to ComputerCraft's existing computers: same 16x resolution, similar contrast, but with a slightly warmer, 80s-electronics tone so CC:TV blocks read as their own family.

The camera gets designed first — it's the face of the mod and sets the style for everything else. Blocks that need art: the camera (wall and pole mounts, with clear facing and states for idle / recording / locked), the capture card, the VCR deck (tape slot, deck number, activity light, states for empty / idle / writing / reading), the RAID controller (healthy / degraded / rebuilding), the playback deck, and the microphone. Items: the tape, with its writable label. GUIs need art too — the export screen, the array screen, tape slots — and usually take more work than people expect.

---

## 11. Build order

Each step is useful on its own:

1. **Microphone + intercom** — working phones and PA systems, immediately fun
2. **Capture card + tapes** — screen recording with GIF export
3. **Live camera viewing** — monitor walls
4. **VCR decks + arrays** — spanning, striping, mirroring, loop mode
5. **Computer-readable camera frames** — motion detection, scripted security
6. **Video calling** — camera + microphone + speaker, tied together with a reference calling app
7. **Later:** playback deck, MP4 export, parity arrays, tape shelves

---

## 12. Launch

A mod overview video is planned for release. The five-second version of the pitch: pull a tape out of a humming VCR rack, walk it across town, slot it into a deck in a courtroom, and watch the crime play back on the big screen.
