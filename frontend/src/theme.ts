import { definePreset } from '@primeuix/themes'
import Aura from '@primeuix/themes/aura'

export default definePreset(Aura, {
  primitive: {
    borderRadius: { sm: '6px', md: '8px', lg: '10px', xl: '16px' },
  },
  semantic: {
    typography: { fontFamily: 'var(--app-font)', fontSize: '0.875rem' },
    focusRing: { width: '2px', offset: '3px' },
    content: { borderColor: 'var(--app-border)' },
    primary: {
      50: '#faf8f5',
      100: '#efebe6',
      200: '#ddd7d0',
      300: '#bcb5ad',
      400: '#8a837c',
      500: '#343434',
      600: '#292929',
      700: '#222222',
      800: '#1c1c1c',
      900: '#171717',
      950: '#101010',
      color: '{primary.500}',
      contrastColor: '#ffffff',
      hoverColor: '{primary.600}',
      activeColor: '{primary.700}',
    },
    surface: {
      50: '#faf8f5',
      100: '#f2efeb',
      200: '#e5e0da',
      300: '#d4cdc5',
      400: '#a89f95',
      500: '#78716c',
      600: '#57534e',
      700: '#44403c',
      800: '#292524',
      900: '#1c1917',
      950: '#0c0a09',
    },
    text: {
      color: '#262626',
      hoverColor: '#171717',
      mutedColor: '#716b64',
      hoverMutedColor: '#57534e',
    },
  },
  components: {
    button: {
      root: { paddingX: '0.875rem', paddingY: '0.625rem', borderRadius: '10px' },
    },
    card: {
      root: { borderRadius: '16px', shadow: 'var(--app-shadow)' },
    },
    sidebar: {
      layout: { background: 'var(--app-canvas)' },
      main: { background: 'var(--app-canvas)' },
      panel: { background: 'var(--app-sidebar)' },
      header: { padding: '1.5rem 0.75rem', gap: '0.75rem' },
      content: { gap: '0.5rem' },
      menuButton: {
        height: '2.5rem',
        padding: '0.625rem 0.75rem',
        borderRadius: '10px',
        fontSize: '0.875rem',
        fontWeight: '500',
        gap: '0.75rem',
        activeBackground: 'var(--app-surface)',
        activeColor: '{text.color}',
        focusBackground: '{surface.100}',
      },
    },
    avatar: {
      root: {
        background: '{primary.100}',
        color: '{primary.700}',
      },
    },
  },
})
