import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, Navigate, RouterProvider } from 'react-router'
import './index.css'
import Layout from './components/Layout.tsx'
import AdminPage from './pages/AdminPage.tsx'
import CartPage from './pages/CartPage.tsx'
import NotFoundPage from './pages/NotFoundPage.tsx'
import ProductsPage from './pages/ProductsPage.tsx'

const router = createBrowserRouter([
  {
    element: <Layout />,
    children: [
      { index: true, element: <Navigate to="/products" replace /> },
      { path: 'products', element: <ProductsPage /> },
      { path: 'cart', element: <CartPage /> },
      { path: 'admin', element: <AdminPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <RouterProvider router={router} />
  </StrictMode>,
)
