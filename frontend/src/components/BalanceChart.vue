<script setup lang="ts">
import { computed } from 'vue'
import type { Chart as ChartJs, ChartOptions, Plugin } from 'chart.js'
import Chart from 'primevue/chart'
import type { AccountChange, BalancePoint } from '../api/InvestmentsService'
import { formatCompactMoney, formatDay, formatMoney } from '../investments/history'

const props = defineProps<{
  points: BalancePoint[]
  /** Names the series for screen readers and the tooltip. */
  label: string
  color: string
  height: string
  added?: AccountChange[]
  dropped?: AccountChange[]
}>()

const GRID = '#f0f0f0'
const TICK = '#9e9e9e'
const MARKER = '#cfcfcf'
const CROSSHAIR = '#9e9e9e'

const spansYears = computed(() => {
  const first = props.points[0]?.date
  const last = props.points[props.points.length - 1]?.date
  return first != null && last != null && first.slice(0, 4) !== last.slice(0, 4)
})

// Labels for each day's markers, which the tooltip lists under the value.
const changesByDate = computed(() => {
  const changes = new Map<string, string[]>()
  const add = (date: string, text: string) =>
    changes.set(date, [...(changes.get(date) ?? []), text])
  for (const change of props.added ?? []) add(change.date, `${change.name} added`)
  for (const change of props.dropped ?? []) add(change.date, `${change.name} removed`)
  return changes
})

const chartData = computed(() => ({
  labels: props.points.map((point) => point.date),
  datasets: [
    {
      label: props.label,
      data: props.points.map((point) => point.value),
      borderColor: props.color,
      backgroundColor: `${props.color}1a`,
      borderWidth: 2,
      borderJoinStyle: 'round' as const,
      borderCapStyle: 'round' as const,
      fill: 'start',
      pointRadius: 0,
      pointHoverRadius: 4,
      pointHoverBackgroundColor: props.color,
      pointHoverBorderColor: '#ffffff',
      pointHoverBorderWidth: 2,
      tension: 0,
    },
  ],
}))

const chartOptions = computed<ChartOptions<'line'>>(() => ({
  responsive: true,
  maintainAspectRatio: false,
  animation: false,
  interaction: { mode: 'index', intersect: false },
  layout: { padding: { top: 8 } },
  scales: {
    x: {
      grid: { display: false },
      border: { display: false },
      ticks: {
        color: TICK,
        maxRotation: 0,
        autoSkip: true,
        maxTicksLimit: 6,
        callback(_value, index) {
          const date = props.points[index]?.date
          return date ? formatDay(date, spansYears.value) : ''
        },
      },
    },
    y: {
      grid: { color: GRID },
      border: { display: false },
      ticks: {
        color: TICK,
        maxTicksLimit: 5,
        callback: (value) => formatCompactMoney(Number(value)),
      },
    },
  },
  plugins: {
    legend: { display: false },
    tooltip: {
      displayColors: false,
      backgroundColor: '#171717',
      padding: 10,
      cornerRadius: 8,
      callbacks: {
        title: (items) => {
          const date = props.points[items[0]?.dataIndex ?? 0]?.date
          return date ? formatDay(date, true) : ''
        },
        label: (item) => formatMoney(Number(item.raw)),
        afterBody: (items) => {
          const date = props.points[items[0]?.dataIndex ?? 0]?.date
          return date ? (changesByDate.value.get(date) ?? []) : []
        },
      },
    },
  },
}))

/** Hairlines on the days accounts joined or left, and a crosshair at the hovered day. */
const guides: Plugin<'line'> = {
  id: 'balanceGuides',
  afterDatasetsDraw(chart: ChartJs) {
    const { ctx, chartArea, scales } = chart
    const x = scales.x
    if (!x) return
    const labels = chart.data.labels as string[]
    const line = (at: number, color: string) => {
      ctx.save()
      ctx.strokeStyle = color
      ctx.lineWidth = 1
      ctx.beginPath()
      ctx.moveTo(Math.round(at) + 0.5, chartArea.top)
      ctx.lineTo(Math.round(at) + 0.5, chartArea.bottom)
      ctx.stroke()
      ctx.restore()
    }
    for (const date of changesByDate.value.keys()) {
      const index = labels.indexOf(date)
      if (index >= 0) line(x.getPixelForValue(index), MARKER)
    }
    const active = chart.tooltip?.getActiveElements()[0]
    if (active) line(active.element.x, CROSSHAIR)
  },
}

const summary = computed(() => {
  const first = props.points[0]
  const last = props.points[props.points.length - 1]
  if (!first || !last) return props.label
  return `${props.label} went from ${formatMoney(first.value)} on ${formatDay(first.date, true)} to ${formatMoney(last.value)} on ${formatDay(last.date, true)}.`
})
</script>

<template>
  <div class="balance-chart" :style="{ height }" role="img" :aria-label="summary">
    <Chart type="line" :data="chartData" :options="chartOptions" :plugins="[guides]" />
  </div>
</template>

<style scoped>
.balance-chart {
  position: relative;
}

.balance-chart :deep(.p-chart) {
  height: 100%;
}

.balance-chart :deep(canvas) {
  width: 100% !important;
  height: 100% !important;
}
</style>
