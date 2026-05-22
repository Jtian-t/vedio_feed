## ADDED Requirements

### Requirement: Audio-based A/V sync
When an audio track is present, the video player SHALL use the audio playback position as the primary clock for synchronizing video frame rendering.

#### Scenario: Video syncs to audio clock
- **WHEN** audio track is active and video frame PTS is ready
- **THEN** the player SHALL render the video frame when `videoPTS <= audioPlaybackPosition + tolerance`

#### Scenario: No audio falls back to wall clock
- **WHEN** no audio track is available
- **THEN** the player SHALL use wall clock synchronization as fallback

### Requirement: A/V sync tolerance
The player SHALL render video frames within an acceptable sync window relative to the audio clock.

#### Scenario: Frame within tolerance renders immediately
- **WHEN** video frame PTS is within 30ms of the current audio playback position
- **THEN** the player SHALL render the frame immediately

#### Scenario: Frame ahead of audio is delayed
- **WHEN** video frame PTS is more than 30ms ahead of the audio playback position
- **THEN** the player SHALL delay rendering until the audio clock catches up

### Requirement: First frame immediate render
The first video frame SHALL render immediately without waiting for synchronization, to minimize perceived startup latency.

#### Scenario: First frame renders on decode
- **WHEN** the first decoded video frame is available
- **THEN** the player SHALL render it immediately and establish sync reference points
