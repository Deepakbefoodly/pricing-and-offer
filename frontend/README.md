# Frontend

Small React + TypeScript + Vite app (React Router, Tailwind CSS) that demonstrates the backend's behaviour.

```bash
npm install
npm run dev      # http://localhost:5173
npm run build    # type-check + production build
npm run lint
```

The API base URL defaults to `http://localhost:8080`; override it with `VITE_API_BASE_URL` (see `.env.example`).
The dev server is pinned to port 5173 because that is the origin the backend allows via CORS.
