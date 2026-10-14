<template>
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" class="agent-icon">
    <path v-for="(path, index) in paths[name] || paths.spark" :key="index" :d="path" />
  </svg>
</template>

<script setup>
defineProps({ name: { type: String, default: 'spark' } })
const paths = {
  spark: ['M12 3l2.7 6.3L21 12l-6.3 2.7L12 21l-2.7-6.3L3 12l6.3-2.7L12 3Z'],
  plus: ['M12 5v14M5 12h14'],
  panel: ['M4 4h16v16H4Z', 'M9 4v16', 'M6.5 8v3'],
  arrow: ['M12 19V5', 'm6 11 6-6 6 6'],
  chevron: ['m9 5 7 7-7 7'],
  down: ['m6 9 6 6 6-6'],
  book: ['M4 5h6a2 2 0 0 1 2 2v14a3 3 0 0 0-3-2H4V5Z', 'M20 5h-6a2 2 0 0 0-2 2v14a3 3 0 0 1 3-2h5V5Z'],
  file: ['M6 3h8l4 4v14H6V3Z', 'M14 3v5h4', 'M9 12h6M9 16h6'],
  search: ['M10.5 4a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13Z', 'm16 16 5 5'],
  chat: ['M4 4h16v13H9l-5 4V4Z', 'M8 9h8M8 13h5'],
  close: ['m6 6 12 12M6 18 18 6'],
  settings: ['M4 7h16M4 17h16', 'M8 4v6M16 14v6'],
  copy: ['M9 9h12v12H9Z', 'M5 15H3V3h12v2'],
  check: ['m5 12 4 4L19 6'],
  link: ['m10 14 4-4', 'M8 16l-1 1a3.5 3.5 0 0 1-5-5l4-4a3.5 3.5 0 0 1 5 0', 'M16 8l1-1a3.5 3.5 0 0 1 5 5l-4 4a3.5 3.5 0 0 1-5 0']
}
</script>

<style scoped>
.agent-icon { width: 1em; height: 1em; flex-shrink: 0; vertical-align: middle; }
</style>
