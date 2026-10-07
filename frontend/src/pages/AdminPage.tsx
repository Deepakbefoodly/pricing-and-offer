import CouponsAdmin from './admin/CouponsAdmin.tsx'
import ProductsAdmin from './admin/ProductsAdmin.tsx'
import ReportAdmin from './admin/ReportAdmin.tsx'

export default function AdminPage() {
  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">Admin</h1>
      <ReportAdmin />
      <CouponsAdmin />
      <ProductsAdmin />
    </div>
  )
}
