## ADDED Requirements

### Requirement: Sufficient AudioTrack buffer
The AudioTrack buffer size SHALL be large enough to absorb decode rate variations without audible stuttering.

#### Scenario: Buffer is at least 4x minimum
- **WHEN** AudioTrack is created
- **THEN** the buffer size SHALL be at least 4 times the value returned by `AudioTrack.getMinBufferSize()`

### Requirement: Float PCM format detection
The player SHALL detect when the MediaCodec audio decoder outputs float PCM and adapt the AudioTrack configuration accordingly.

#### Scenario: Float output detected and handled
- **WHEN** the audio decoder output format contains `KEY_PCM_ENCODING = ENCODING_PCM_FLOAT`
- **THEN** the player SHALL recreate the AudioTrack with `ENCODING_PCM_FLOAT` encoding

#### Scenario: 16-bit output used by default
- **WHEN** the audio decoder output format specifies 16-bit PCM or no encoding info
- **THEN** the player SHALL use the default `ENCODING_PCM_16BIT` AudioTrack

### Requirement: Audio clock accuracy
The player SHALL accurately track audio playback position using `AudioTrack.getPlaybackHeadPosition()`.

#### Scenario: Audio position computed correctly
- **WHEN** audio is playing
- **THEN** `getAudioTimeUs()` SHALL return the current media time in microseconds based on frames played and sample rate

#### Scenario: uint32 overflow handled
- **WHEN** `playbackHeadPosition` wraps around uint32 max
- **THEN** the player SHALL mask the high bits to get the correct unsigned value
