/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        surface: {
          DEFAULT: '#0a0a14',
          50: '#0f0f1a',
          100: '#14142a',
          200: '#1a1a3a',
        },
      },
      animation: {
        'in': 'animateIn 0.3s ease-out',
        'slide-in-from-right': 'slideInFromRight 0.3s ease-out',
        'slide-in-from-top': 'slideInFromTop 0.3s ease-out',
        'fade-in': 'fadeIn 0.2s ease-out',
      },
      keyframes: {
        animateIn: {
          '0%': { opacity: '0', transform: 'scale(0.95)' },
          '100%': { opacity: '1', transform: 'scale(1)' },
        },
        slideInFromRight: {
          '0%': { opacity: '0', transform: 'translateX(1rem)' },
          '100%': { opacity: '1', transform: 'translateX(0)' },
        },
        slideInFromTop: {
          '0%': { opacity: '0', transform: 'translateY(-1rem)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        fadeIn: {
          '0%': { opacity: '0' },
          '100%': { opacity: '1' },
        },
      },
    },
  },
  plugins: [],
};
