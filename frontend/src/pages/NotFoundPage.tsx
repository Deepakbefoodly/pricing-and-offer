import { Link } from 'react-router'

export default function NotFoundPage() {
  return (
    <section>
      <h1 className="text-xl font-semibold">Page not found</h1>
      <Link to="/products" className="mt-2 inline-block text-sm text-blue-600 hover:underline">
        Back to products
      </Link>
    </section>
  )
}
