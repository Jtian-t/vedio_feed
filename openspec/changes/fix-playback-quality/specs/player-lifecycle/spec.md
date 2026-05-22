## ADDED Requirements

### Requirement: Sequence-based task cancellation
The player SHALL use a sequence counter to ensure only the latest `prepareAndPlay` call's task runs, and old tasks exit cleanly.

#### Scenario: New prepareAndPlay cancels old task
- **WHEN** `prepareAndPlay()` is called while a previous task is running
- **THEN** the previous task SHALL detect `seq != currentSeq` and exit its decode loop

#### Scenario: Old task releases its own resources
- **WHEN** an old task exits due to sequence mismatch
- **THEN** it SHALL release its MediaCodec, MediaExtractor, and AudioTrack in its `finally` block

### Requirement: Decode loop crash protection
The player SHALL catch exceptions in decode loops to prevent application crashes.

#### Scenario: Codec exception does not crash app
- **WHEN** a MediaCodec operation throws an exception in `decodeVideo` or `decodeAudio`
- **THEN** the player SHALL log the error and exit the loop gracefully

#### Scenario: Cancelled task exception is rethrown
- **WHEN** the decode coroutine is cancelled via `CancellationException`
- **THEN** the player SHALL rethrow it to allow proper coroutine cancellation

### Requirement: Clean release
The `release()` method SHALL fully stop playback and reset all state without leaving dangling resources.

#### Scenario: Release stops current playback
- **WHEN** `release()` is called
- **THEN** the current task is cancelled and all state is reset to IDLE

#### Scenario: Release is safe to call multiple times
- **WHEN** `release()` is called when no task is running
- **THEN** no exception is thrown and state remains IDLE
