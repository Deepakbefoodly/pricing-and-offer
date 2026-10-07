import CouponsAdmin from './admin/CouponsAdmin.tsx'
import ProductsAdmin from './admin/ProductsAdmin.tsx'

export default function AdminPage() {
  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">Admin</h1>
      <CouponsAdmin />
      <ProductsAdmin />
      <p className="text-sm text-slate-500">The report (Slice 6) will appear here.</p>
    </div>
  )
}
