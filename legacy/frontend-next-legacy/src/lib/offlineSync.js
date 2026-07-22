import { get, set, update, del } from 'idb-keyval';

const SYNC_QUEUE_KEY = 'dental-crm-sync-queue';

export const getSyncQueue = async () => {
  return (await get(SYNC_QUEUE_KEY)) || [];
};

export const enqueueRequest = async (config) => {
  // Config.data might be a JSON string, we should store it as is or parse it
  let requestData = config.data;
  
  const requestPayload = {
    id: Date.now().toString() + Math.random().toString(36).substr(2, 9),
    method: config.method,
    url: config.url,
    data: requestData,
    headers: config.headers,
    timestamp: new Date().toISOString(),
  };

  await update(SYNC_QUEUE_KEY, (val) => {
    const queue = val || [];
    return [...queue, requestPayload];
  });
  
  return requestPayload;
};

export const clearSyncQueue = async () => {
  await del(SYNC_QUEUE_KEY);
};

export const processQueue = async (axiosInstance) => {
  if (!navigator.onLine) return;

  const queue = await getSyncQueue();
  if (queue.length === 0) return;

  // Process items sequentially to maintain order
  for (const item of queue) {
    try {
      let parsedData = item.data;
      if (typeof item.data === 'string') {
          try {
              parsedData = JSON.parse(item.data);
          } catch(e) {}
      }

      // Inject client_timestamp into data payload for auditing
      if (parsedData && typeof parsedData === 'object' && !(parsedData instanceof FormData)) {
         parsedData.client_timestamp = item.timestamp;
      }

      const replayConfig = {
        method: item.method,
        url: item.url,
        data: parsedData,
        headers: {
          ...item.headers,
          'X-Client-Timestamp': item.timestamp,
        }
      };
      
      await axiosInstance(replayConfig);
      
      // Remove from queue on success
      await update(SYNC_QUEUE_KEY, (val) => {
        return (val || []).filter(q => q.id !== item.id);
      });
    } catch (error) {
      // If error is 4xx (validation/auth) discard it to prevent blocking the queue forever
      // If error is 5xx or network, keep it in queue to retry later
      if (error.response && error.response.status >= 400 && error.response.status < 500) {
        await update(SYNC_QUEUE_KEY, (val) => {
           return (val || []).filter(q => q.id !== item.id);
        });
      }
    }
  }
};
