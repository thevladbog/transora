import { defineConfig } from 'orval';

export default defineConfig({
  transora: {
    input: {
      target: '../../openapi/transora.openapi.json',
    },
    output: {
      mode: 'tags-split',
      target: './src/generated/endpoints',
      schemas: './src/generated/model',
      client: 'react-query',
      httpClient: 'fetch',
      override: {
        mutator: {
          path: './src/mutator.ts',
          name: 'customInstance',
        },
        query: {
          useQuery: true,
          useMutation: true,
        },
      },
    },
  },
});
