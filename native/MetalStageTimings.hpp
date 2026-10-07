#pragma once
#import <Metal/Metal.h>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace metal {
// Optional diagnostics at existing encoder boundaries. These spans may overlap and must not
// be summed as a critical path. No extra encoder, ordering fence or GPU wait is introduced.
struct StageTimingTotals {
    uint64_t count = 0, vertexNanos = 0, fragmentNanos = 0, spanNanos = 0;
};
struct StageTimingPass {
    std::string label;
    bool drawIssued = false;
};
struct StageTimingStatistics {
    std::mutex mutex;
    bool requested = false, supported = false;
    uint64_t sampledSubmissions = 0, invalidSamples = 0, truncatedPasses = 0;
    uint64_t emptyPasses = 0, implausibleSamples = 0;
    std::unordered_map<std::string, StageTimingTotals> stages;
    void record(id<MTLCommandBuffer> command, id<MTLCounterSampleBuffer> samples,
                const std::vector<StageTimingPass>& passes);
    NSString* json();
};
struct StageTimingFrame {
    id<MTLCounterSampleBuffer> samples;
    std::vector<StageTimingPass> passes;
    static constexpr size_t NoPass = static_cast<size_t>(-1);
    size_t currentPass = NoPass;
    bool active = false;
    void reset(bool shouldSample);
    void finishPass(bool drawIssued);
    void attach(id<MTLDevice> device, MTLRenderPassDescriptor* descriptor, NSString* label,
                const std::shared_ptr<StageTimingStatistics>& statistics);
};
}
