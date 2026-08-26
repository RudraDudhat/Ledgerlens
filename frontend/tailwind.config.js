/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
        mono: ['"JetBrains Mono"', 'ui-monospace', 'monospace'],
      },
      colors: {
        ink: 'var(--ink)',
        muted: 'var(--ink-muted)',
        faint: 'var(--ink-faint)',
        surface: 'var(--surface)',
        raised: 'var(--surface-raised)',
        line: 'var(--line)',
        accent: 'var(--accent)',
        received: 'var(--received)',
        held: 'var(--held)',
        lost: 'var(--lost)',
        fees: 'var(--fees)',
      },
    },
  },
  plugins: [],
}
