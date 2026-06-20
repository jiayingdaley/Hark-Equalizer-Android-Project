#pragma once

#include <atomic>
#include <vector>
#include <cstddef>

/**
 * A simple Single-Producer, Single-Consumer (SPSC) Lock-Free Ring Buffer.
 * Used for safely transferring audio data from Kotlin (Java thread) to Oboe (audio callback thread)
 * without blocking or causing latency spikes.
 */
template <typename T>
class LockFreeQueue {
public:
    explicit LockFreeQueue(size_t capacity)
        : mCapacity(capacity + 1), mBuffer(mCapacity) {
        mHead.store(0, std::memory_order_relaxed);
        mTail.store(0, std::memory_order_relaxed);
    }

    // Push data (Called by Producer - Kotlin JNI)
    bool push(const T* data, size_t count) {
        size_t currentTail = mTail.load(std::memory_order_relaxed);
        size_t currentHead = mHead.load(std::memory_order_acquire);
        
        size_t availableSpace = (currentHead - currentTail - 1 + mCapacity) % mCapacity;
        if (availableSpace < count) {
            return false; // Not enough space (Buffer overrun)
        }

        for (size_t i = 0; i < count; ++i) {
            mBuffer[(currentTail + i) % mCapacity] = data[i];
        }

        mTail.store((currentTail + count) % mCapacity, std::memory_order_release);
        return true;
    }

    // Pop data (Called by Consumer - Oboe Callback)
    size_t pop(T* data, size_t count) {
        size_t currentHead = mHead.load(std::memory_order_relaxed);
        size_t currentTail = mTail.load(std::memory_order_acquire);

        size_t availableData = (currentTail - currentHead + mCapacity) % mCapacity;
        size_t readCount = std::min(availableData, count);

        if (readCount == 0) return 0;

        for (size_t i = 0; i < readCount; ++i) {
            data[i] = mBuffer[(currentHead + i) % mCapacity];
        }

        mHead.store((currentHead + readCount) % mCapacity, std::memory_order_release);
        return readCount;
    }

    void clear() {
        mHead.store(0, std::memory_order_relaxed);
        mTail.store(0, std::memory_order_relaxed);
    }

private:
    const size_t mCapacity;
    std::vector<T> mBuffer;
    std::atomic<size_t> mHead;
    std::atomic<size_t> mTail;
};
