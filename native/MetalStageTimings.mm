#include "MetalStageTimings.hpp"
#include <algorithm>
#include <cmath>

namespace metal {
static constexpr NSUInteger MaxSamples = 4096;
void StageTimingFrame::reset(bool shouldSample) {
    passes.clear();
    currentPass = NoPass;
    active = shouldSample;
}
void StageTimingFrame::finishPass(bool drawIssued) {
    if (currentPass < passes.size()) passes[currentPass].drawIssued = drawIssued;
    currentPass = NoPass;
}
void StageTimingFrame::attach(id<MTLDevice> device, MTLRenderPassDescriptor* descriptor,
                             NSString* label, const std::shared_ptr<StageTimingStatistics>& stats) {
    // A resumed pass inherits its old descriptor; never overwrite that pass's earlier samples.
    auto attachment = descriptor.sampleBufferAttachments[0];
    attachment.sampleBuffer = nil;
    attachment.startOfVertexSampleIndex = MTLCounterDontSample;
    attachment.endOfVertexSampleIndex = MTLCounterDontSample;
    attachment.startOfFragmentSampleIndex = MTLCounterDontSample;
    attachment.endOfFragmentSampleIndex = MTLCounterDontSample;
    currentPass = NoPass;
    if (!active) return;
    if (passes.size() * 4 + 4 > MaxSamples) {
        std::lock_guard lock(stats->mutex);
        ++stats->truncatedPasses;
        return;
    }
    if (!samples) {
        id<MTLCounterSet> timestamps = nil;
        for (id<MTLCounterSet> set in device.counterSets)
            if ([set.name isEqualToString:MTLCommonCounterSetTimestamp]) { timestamps = set; break; }
        MTLCounterSampleBufferDescriptor* spec = [MTLCounterSampleBufferDescriptor new];
        spec.counterSet = timestamps;
        spec.storageMode = MTLStorageModeShared;
        spec.sampleCount = MaxSamples;
        NSError* error = nil;
        if (timestamps) samples = [device newCounterSampleBufferWithDescriptor:spec error:&error];
        if (!samples) {
            active = false;
            std::lock_guard lock(stats->mutex);
            ++stats->invalidSamples;
            return;
        }
    }
    NSUInteger first = passes.size() * 4;
    std::string name = (label ?: @"unlabelled").UTF8String;
    if (name.size() > 160) name.resize(160);
    currentPass = passes.size();
    passes.push_back({std::move(name), false});
    attachment.sampleBuffer = samples;
    attachment.startOfVertexSampleIndex = first;
    attachment.endOfVertexSampleIndex = first + 1;
    attachment.startOfFragmentSampleIndex = first + 2;
    attachment.endOfFragmentSampleIndex = first + 3;
}
void StageTimingStatistics::record(id<MTLCommandBuffer> command,
                                  id<MTLCounterSampleBuffer> samples,
                                  const std::vector<StageTimingPass>& passes) {
    if (passes.empty()) return;
    NSData* data = command.status == MTLCommandBufferStatusCompleted
        ? [samples resolveCounterRange:NSMakeRange(0, passes.size() * 4)] : nil;
    const auto* values = static_cast<const MTLCounterResultTimestamp*>(data.bytes);
    // Stage-boundary counter intervals on Apple GPUs are nanoseconds; command buffer
    // intervals are seconds. Compare durations, never their unrelated absolute epochs.
    // Counter slots for stages without invocations can retain old values. Reject a mixed
    // old/new pair even when each individual stage happens to have a positive duration.
    double start = command.GPUStartTime, end = command.GPUEndTime;
    bool bounded = std::isfinite(start) && std::isfinite(end) && start > 0 && end > start;
    double maximumSpan = bounded ? (end - start) * 1e9 * 1.02 + 1e6 : 0;
    std::lock_guard lock(mutex);
    ++sampledSubmissions;
    for (size_t i = 0; i < passes.size(); ++i) {
        // Clear/load/store-only encoders do not execute both shader stages, so their four
        // slots cannot establish a trustworthy stage interval. Do not attribute stale data.
        if (!passes[i].drawIssued) { ++emptyPasses; continue; }
        if (data.length < (i + 1) * 4 * sizeof(MTLCounterResultTimestamp)) { ++invalidSamples; continue; }
        uint64_t vs = values[i*4].timestamp, ve = values[i*4+1].timestamp;
        uint64_t fs = values[i*4+2].timestamp, fe = values[i*4+3].timestamp;
        if (!vs || !fs || vs == MTLCounterErrorValue || ve == MTLCounterErrorValue ||
            fs == MTLCounterErrorValue || fe == MTLCounterErrorValue || ve < vs || fe < fs) {
            ++invalidSamples;
            continue;
        }
        uint64_t span = std::max(ve, fe) - std::min(vs, fs);
        if (!bounded || static_cast<double>(span) > maximumSpan) {
            ++invalidSamples;
            ++implausibleSamples;
            continue;
        }
        const std::string& name = passes[i].label;
        // Keep dynamic/custom debug labels from making a diagnostic session grow without bound.
        auto& entry = stages[stages.size() < 256 || stages.count(name) ? name : "other"];
        ++entry.count;
        entry.vertexNanos += ve - vs;
        entry.fragmentNanos += fe - fs;
        entry.spanNanos += span;
    }
}
NSString* StageTimingStatistics::json() {
    std::lock_guard lock(mutex);
    NSMutableDictionary* output = [NSMutableDictionary dictionary];
    for (const auto& pair : stages) {
        const auto& item = pair.second;
        NSString* name = [[NSString alloc] initWithBytes:pair.first.data() length:pair.first.size()
                                              encoding:NSUTF8StringEncoding];
        if (!name) name = @"invalid-label";
        output[name] = @{ @"count": @(item.count), @"vertexNanos": @(item.vertexNanos),
                         @"fragmentNanos": @(item.fragmentNanos), @"spanNanos": @(item.spanNanos) };
    }
    NSDictionary* result = @{ @"requested": @(requested), @"supported": @(supported),
        @"sampledSubmissions": @(sampledSubmissions), @"invalidSamples": @(invalidSamples),
        @"truncatedPasses": @(truncatedPasses), @"emptyPasses": @(emptyPasses),
        @"implausibleSamples": @(implausibleSamples), @"stages": output };
    NSData* data = [NSJSONSerialization dataWithJSONObject:result options:0 error:nil];
    return [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
}
}
