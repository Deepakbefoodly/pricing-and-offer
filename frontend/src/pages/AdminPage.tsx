import ProductsAdmin from './admin/ProductsAdmin.tsx'

export default function AdminPage() {
  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">Admin</h1>
      <ProductsAdmin />
      <p className="text-sm text-slate-500">Coupon generation (Slice 5) and the report (Slice 6) will appear here.</p>
    </div>
  )
}
