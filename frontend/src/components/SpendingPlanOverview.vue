<script setup lang="ts">
import { Pencil, RotateCcw } from '@lucide/vue'
import { computed } from 'vue'
import Button from 'primevue/button'
import {
  PLAN_TARGETS,
  formatPlanAmount,
  lineAmount,
  shareOfIncome,
  summarizePlan,
  type BucketId,
  type PlanLineDraft,
} from '../spendingPlan/fromRecurring'
import type { SavedPlan } from '../spendingPlan/savedPlan'

const props = defineProps<{ plan: SavedPlan }>()

const emit = defineEmits<{
  edit: []
  startOver: []
}>()

type SegmentId = BucketId | 'guiltFree'

interface Segment {
  id: SegmentId
  title: string
  /** Categorical slots from the validated dataviz palette, in fixed order. */
  color: string
  target: { min: number; max: number }
}

const SEGMENTS: Segment[] = [
  { id: 'fixedCosts', title: 'Fixed costs', color: '#2a78d6', target: PLAN_TARGETS.fixedCosts },
  { id: 'investments', title: 'Investments', color: '#eb6834', target: PLAN_TARGETS.investments },
  { id: 'savings', title: 'Savings', color: '#1baf7a', target: PLAN_TARGETS.savings },
  {
    id: 'guiltFree',
    title: 'Guilt-free spending',
    color: '#eda100',
    target: PLAN_TARGETS.guiltFree,
  },
]

const BUCKET_CARDS: { id: BucketId; title: string }[] = [
  { id: 'fixedCosts', title: 'Fixed costs' },
  { id: 'investments', title: 'Investments' },
  { id: 'savings', title: 'Savings' },
]

const summary = computed(() =>
  summarizePlan(props.plan.takeHome, props.plan.plan, props.plan.bufferPercent),
)

const segments = computed(() =>
  SEGMENTS.map((segment) => {
    const amount =
      segment.id === 'guiltFree' ? summary.value.guiltFree : summary.value.totals[segment.id]
    const share = amount == null ? null : shareOfIncome(amount, summary.value.income)
    return { ...segment, amount, share, over: isOver(segment, amount, share) }
  }),
)

/** Bar widths are shares of income, or of the plan when it adds up to more than income. */
const barScale = computed(() => {
  const planned =
    summary.value.totals.fixedCosts +
    summary.value.totals.investments +
    summary.value.totals.savings
  return Math.max(summary.value.income ?? 0, planned)
})

const barSegments = computed(() =>
  segments.value.filter((segment) => (segment.amount ?? 0) > 0 && barScale.value > 0),
)

const barLabel = computed(() =>
  segments.value
    .filter((segment) => segment.share != null)
    .map((segment) => `${segment.title} ${segment.share}%`)
    .join(', '),
)

const savedOn = computed(() =>
  new Intl.DateTimeFormat('en-US', { dateStyle: 'medium' }).format(new Date(props.plan.updatedAt)),
)

function isOver(segment: Segment, amount: number | null, share: number | null) {
  if (segment.id === 'guiltFree') return amount != null && amount < 0
  return segment.id === 'fixedCosts' && share != null && share > segment.target.max
}

function targetLabel(target: { min: number; max: number }) {
  return target.min === target.max
    ? `Target ~${target.min}%`
    : `Target ${target.min}–${target.max}%`
}

function segmentWidth(amount: number | null) {
  return `${((amount ?? 0) / barScale.value) * 100}%`
}

function hasBuffer(bucket: BucketId) {
  return bucket === 'fixedCosts' && summary.value.buffer > 0
}

/** Lines left blank don't belong on the overview. */
function plannedLines(lines: PlanLineDraft[]) {
  return lines.filter((line) => lineAmount(line) != null)
}
</script>

<template>
  <div class="plan-overview">
    <div class="overview-actions">
      <p class="saved-on">Saved {{ savedOn }}</p>
      <Button label="Start over" severity="secondary" text @click="emit('startOver')">
        <template #icon>
          <RotateCcw :size="15" :stroke-width="1.75" aria-hidden="true" />
        </template>
      </Button>
      <Button label="Edit plan" severity="secondary" aria-haspopup="dialog" @click="emit('edit')">
        <template #icon>
          <Pencil :size="15" :stroke-width="1.75" aria-hidden="true" />
        </template>
      </Button>
    </div>

    <section class="panel summary-panel" aria-labelledby="plan-income-heading">
      <div class="income">
        <h2 id="plan-income-heading">Monthly income</h2>
        <p v-if="summary.income != null" class="income-amount" aria-label="Plan income">
          {{ formatPlanAmount(summary.income) }}
        </p>
        <p v-else class="income-missing">Add your take-home pay to see how it splits.</p>
        <p v-if="summary.fromPaycheck > 0 && plan.takeHome != null" class="income-detail">
          {{ formatPlanAmount(plan.takeHome) }} take-home +
          {{ formatPlanAmount(summary.fromPaycheck) }} from your paycheck
        </p>
      </div>

      <div
        v-if="summary.income != null && barSegments.length"
        class="split-bar"
        role="img"
        :aria-label="`How income splits: ${barLabel}`"
      >
        <span
          v-for="segment in barSegments"
          :key="segment.id"
          class="split-segment"
          :style="{ width: segmentWidth(segment.amount), background: segment.color }"
          :title="`${segment.title}: ${formatPlanAmount(segment.amount ?? 0)}`"
        />
      </div>

      <ul class="legend">
        <li v-for="segment in segments" :key="segment.id" class="legend-item">
          <span class="swatch" :style="{ background: segment.color }" aria-hidden="true" />
          <span class="legend-title">{{ segment.title }}</span>
          <span
            class="legend-amount"
            :class="{ 'legend-over': segment.over }"
            :aria-label="`${segment.title} amount`"
          >
            {{ segment.amount == null ? '—' : formatPlanAmount(segment.amount) }}
          </span>
          <span class="legend-meta">
            <span
              v-if="segment.share != null"
              class="legend-share"
              :class="{ 'legend-over': segment.over }"
            >
              {{ segment.share }}%<template v-if="segment.over"> · over target</template>
            </span>
            <span>{{ targetLabel(segment.target) }}</span>
          </span>
        </li>
      </ul>
    </section>

    <div class="bucket-grid">
      <section
        v-for="bucket in BUCKET_CARDS"
        :key="bucket.id"
        class="panel bucket-card"
        :aria-labelledby="`overview-${bucket.id}-heading`"
      >
        <header class="bucket-header">
          <h2 :id="`overview-${bucket.id}-heading`">{{ bucket.title }}</h2>
          <span class="bucket-total">{{ formatPlanAmount(summary.totals[bucket.id]) }}</span>
        </header>

        <ul
          v-if="plannedLines(plan.plan[bucket.id]).length || hasBuffer(bucket.id)"
          class="line-list"
        >
          <li v-for="(line, index) in plannedLines(plan.plan[bucket.id])" :key="index">
            <div class="line-row">
              <span class="line-name">
                {{ line.name || 'Untitled' }}
                <span v-if="line.fromPaycheck" class="paycheck-tag">From paycheck</span>
              </span>
              <span class="line-amount">{{ formatPlanAmount(lineAmount(line) ?? 0) }}</span>
            </div>
            <ul v-if="line.items.length" class="item-list">
              <li v-for="(item, itemIndex) in line.items" :key="itemIndex" class="line-row">
                <span class="line-name">{{ item.name || 'Untitled' }}</span>
                <span class="line-amount">{{ formatPlanAmount(item.amount ?? 0) }}</span>
              </li>
            </ul>
          </li>
          <li v-if="hasBuffer(bucket.id)" class="line-row buffer-line">
            <span class="line-name">Miscellaneous buffer ({{ plan.bufferPercent }}%)</span>
            <span class="line-amount">{{ formatPlanAmount(summary.buffer) }}</span>
          </li>
        </ul>
        <p v-else class="bucket-empty">Nothing planned yet.</p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.plan-overview {
  display: grid;
  gap: 1.25rem;
}

.overview-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
}

.saved-on {
  margin: 0 auto 0 0;
  color: var(--app-text-secondary);
  font-size: 0.8125rem;
}

.summary-panel {
  display: grid;
  gap: 1.25rem;
  padding: 1.5rem;
}

h2 {
  margin: 0;
  font-size: 1.0625rem;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.income h2 {
  color: var(--app-text-secondary);
  font-size: 0.8125rem;
  font-weight: 500;
  letter-spacing: 0;
}

.income-amount {
  margin: 0.25rem 0 0;
  font-size: 2rem;
  font-weight: 550;
  letter-spacing: -0.04em;
  font-variant-numeric: tabular-nums;
}

.income-missing,
.income-detail {
  margin: 0.35rem 0 0;
  color: var(--app-text-secondary);
  font-size: 0.8125rem;
}

.split-bar {
  display: flex;
  gap: 2px;
  height: 0.75rem;
  overflow: hidden;
  border-radius: 4px;
}

.split-segment {
  min-width: 2px;
}

.legend {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(11rem, 1fr));
  gap: 1rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

.legend-item {
  display: grid;
  grid-template-columns: auto 1fr;
  column-gap: 0.5rem;
  row-gap: 0.15rem;
  align-items: center;
}

.swatch {
  width: 0.625rem;
  height: 0.625rem;
  border-radius: 2px;
}

.legend-title {
  color: var(--app-text-secondary);
  font-size: 0.8125rem;
}

.legend-amount,
.legend-meta {
  grid-column: 2;
}

.legend-amount {
  font-size: 1.125rem;
  font-weight: 550;
  font-variant-numeric: tabular-nums;
}

.legend-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0.15rem 0.5rem;
  color: var(--app-text-subdued);
  font-size: 0.75rem;
}

.legend-share {
  color: var(--app-text-secondary);
  font-size: 0.8125rem;
  font-weight: 550;
  font-variant-numeric: tabular-nums;
}

.legend-over {
  color: var(--app-danger);
}

.bucket-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(16rem, 1fr));
  gap: 1.25rem;
  align-items: start;
}

.bucket-card {
  display: grid;
  gap: 0.75rem;
  padding: 1.25rem;
}

.bucket-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 1rem;
  padding-bottom: 0.75rem;
  border-bottom: 1px solid var(--app-divider);
}

.bucket-total {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.line-list,
.item-list {
  display: grid;
  gap: 0.5rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

.item-list {
  gap: 0.25rem;
  margin: 0.35rem 0 0.15rem 0.75rem;
  padding-left: 0.75rem;
  border-left: 1px solid var(--app-divider);
  color: var(--app-text-secondary);
  font-size: 0.8125rem;
}

.line-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 1rem;
}

.line-name {
  min-width: 0;
  overflow-wrap: anywhere;
}

.line-amount {
  flex: none;
  font-variant-numeric: tabular-nums;
}

.paycheck-tag {
  margin-left: 0.35rem;
  padding: 0.05rem 0.4rem;
  color: var(--app-text-secondary);
  background: var(--app-inset);
  border-radius: 999px;
  font-size: 0.6875rem;
  white-space: nowrap;
}

.buffer-line {
  padding-top: 0.5rem;
  color: var(--app-text-secondary);
  border-top: 1px dashed var(--app-divider);
}

.bucket-empty {
  margin: 0;
  color: var(--app-text-subdued);
  font-size: 0.875rem;
}

@media (max-width: 640px) {
  .summary-panel,
  .bucket-card {
    padding: 1.25rem;
  }

  .income-amount {
    font-size: 1.625rem;
  }
}
</style>
