import React from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import App from './App'

import './styles/tokens.css'
import './styles/base.css'
import './styles/layout.css'
import './styles/library.css'
import './styles/editor.css'
import './styles/chat.css'
import './styles/console.css'
import './styles/kb.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: false,
      refetchOnWindowFocus: false,
      staleTime: 3_000,
    },
    mutations: {
      retry: false,
    },
  },
})

const container = document.getElementById('root')
if (!container) {
  throw new Error('页面里没有 #root 容器，index.html 被改坏了。')
}

createRoot(container).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </React.StrictMode>,
)
