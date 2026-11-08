// SPDX-License-Identifier: Apache-2.0

export interface ClockProbeSample {
  localMidpointMs: number;
  offsetMs: number;
  rttMs: number;
}

export interface ClockFit {
  offsetMs: number;
  skewPpm: number;
  residualMs: number;
  rttMedianMs: number;
  samplesSeen: number;
  samplesUsed: number;
  selectedSpanMs: number;
}

function percentile(values: number[], fraction: number): number {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((left, right) => left - right);
  const index = Math.min(sorted.length - 1, Math.max(0, Math.ceil(fraction * sorted.length) - 1));
  return sorted[index];
}

export function makeClockSample(
  t1: number,
  t2: number,
  t3: number,
  t4: number,
): ClockProbeSample | null {
  const rttMs = (t4 - t1) - (t3 - t2);
  if (!Number.isFinite(rttMs) || rttMs < 0 || rttMs > 500) return null;

  return {
    localMidpointMs: (t1 + t4) / 2,
    offsetMs: ((t2 - t1) + (t3 - t4)) / 2,
    rttMs,
  };
}

export function estimateClock(
  samples: ClockProbeSample[],
  localNowMs: number,
): ClockFit | null {
  if (samples.length < 32) return null;

  const selectedCount = Math.max(8, Math.ceil(samples.length * 0.25));
  const selected = [...samples]
    .sort((left, right) => left.rttMs - right.rttMs)
    .slice(0, selectedCount);

  const xMean = selected.reduce((sum, sample) => sum + sample.localMidpointMs, 0) / selected.length;
  const yMean = selected.reduce((sum, sample) => sum + sample.offsetMs, 0) / selected.length;

  let denominator = 0;
  let numerator = 0;
  for (const sample of selected) {
    const xDelta = sample.localMidpointMs - xMean;
    const yDelta = sample.offsetMs - yMean;
    denominator += xDelta * xDelta;
    numerator += xDelta * yDelta;
  }

  const slope = denominator > 0 ? numerator / denominator : 0;
  const offsetMs = yMean + slope * (localNowMs - xMean);
  let residualSquares = 0;
  for (const sample of selected) {
    const predicted = yMean + slope * (sample.localMidpointMs - xMean);
    residualSquares += (sample.offsetMs - predicted) ** 2;
  }

  const minTime = Math.min(...selected.map((sample) => sample.localMidpointMs));
  const maxTime = Math.max(...selected.map((sample) => sample.localMidpointMs));

  return {
    offsetMs,
    skewPpm: -slope * 1_000_000,
    residualMs: Math.sqrt(residualSquares / selected.length),
    rttMedianMs: percentile(selected.map((sample) => sample.rttMs), 0.5),
    samplesSeen: samples.length,
    samplesUsed: selected.length,
    selectedSpanMs: maxTime - minTime,
  };
}
