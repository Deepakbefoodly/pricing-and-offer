interface Props {
  title: string
  description: string
}

export default function PagePlaceholder({ title, description }: Props) {
  return (
    <section>
      <h1 className="text-xl font-semibold">{title}</h1>
      <p className="mt-2 text-sm text-slate-600">{description}</p>
    </section>
  )
}
