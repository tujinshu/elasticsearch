/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.action;

import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.delete.DeleteAction;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetAction;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.ml.MlMetaIndex;
import org.elasticsearch.xpack.ml.calendars.Calendar;
import org.elasticsearch.xpack.ml.job.JobManager;
import org.elasticsearch.xpack.ml.job.persistence.JobProvider;
import org.elasticsearch.xpack.ml.utils.ExceptionsHelper;

import java.util.Map;

import static org.elasticsearch.xpack.ClientHelper.ML_ORIGIN;
import static org.elasticsearch.xpack.ClientHelper.executeAsyncWithOrigin;

public class TransportDeleteCalendarEventAction extends HandledTransportAction<DeleteCalendarEventAction.Request,
        DeleteCalendarEventAction.Response> {

    private final Client client;
    private final JobProvider jobProvider;
    private final JobManager jobManager;

    @Inject
    public TransportDeleteCalendarEventAction(Settings settings, ThreadPool threadPool,
                           TransportService transportService, ActionFilters actionFilters,
                           IndexNameExpressionResolver indexNameExpressionResolver,
                           Client client, JobProvider jobProvider, JobManager jobManager) {
        super(settings, DeleteCalendarEventAction.NAME, threadPool, transportService, actionFilters,
                indexNameExpressionResolver, DeleteCalendarEventAction.Request::new);
        this.client = client;
        this.jobProvider = jobProvider;
        this.jobManager = jobManager;
    }

    @Override
    protected void doExecute(DeleteCalendarEventAction.Request request, ActionListener<DeleteCalendarEventAction.Response> listener) {
        final String eventId = request.getEventId();

        GetRequest getRequest = new GetRequest(MlMetaIndex.INDEX_NAME, MlMetaIndex.TYPE, eventId);
        executeAsyncWithOrigin(client, ML_ORIGIN, GetAction.INSTANCE, getRequest, new ActionListener<GetResponse>() {
            @Override
            public void onResponse(GetResponse getResponse) {
                if (getResponse.isExists() == false) {
                    listener.onFailure(new ResourceNotFoundException("No event with id [" + eventId + "]"));
                    return;
                }

                Map<String, Object> source = getResponse.getSourceAsMap();
                String calendarId = (String) source.get(Calendar.ID.getPreferredName());
                if (calendarId == null) {
                    listener.onFailure(ExceptionsHelper.badRequestException("Event [" + eventId + "] does not have a valid "
                            + Calendar.ID.getPreferredName()));
                    return;
                }

                if (calendarId.equals(request.getCalendarId()) == false) {
                    listener.onFailure(ExceptionsHelper.badRequestException(
                            "Event [" + eventId + "] has " + Calendar.ID.getPreferredName() +
                                    " [" + calendarId + "] which does not match the request " + Calendar.ID.getPreferredName() +
                                    " [" + request.getCalendarId() + "]"));
                    return;
                }

                ActionListener<Calendar> calendarListener = ActionListener.wrap(
                        calendar -> {
                            deleteEvent(eventId, calendar, listener);
                        },
                        listener::onFailure
                );

                jobProvider.calendar(calendarId, calendarListener);
            }

            @Override
            public void onFailure(Exception e) {
                listener.onFailure(e);
            }
        });
    }

    private void deleteEvent(String eventId, Calendar calendar, ActionListener<DeleteCalendarEventAction.Response> listener) {
        DeleteRequest deleteRequest = new DeleteRequest(MlMetaIndex.INDEX_NAME, MlMetaIndex.TYPE, eventId);
        deleteRequest.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);

        executeAsyncWithOrigin(client, ML_ORIGIN, DeleteAction.INSTANCE, deleteRequest,
                new ActionListener<DeleteResponse>() {
                    @Override
                    public void onResponse(DeleteResponse response) {

                        if (response.status() == RestStatus.NOT_FOUND) {
                            listener.onFailure(new ResourceNotFoundException("No event with id [" + eventId + "]"));
                        } else {
                            jobManager.updateProcessOnCalendarChanged(calendar.getJobIds());
                            listener.onResponse(new DeleteCalendarEventAction.Response(true));
                        }
                    }

                    @Override
                    public void onFailure(Exception e) {
                        listener.onFailure(ExceptionsHelper.serverError("Could not delete event [" + eventId + "]", e));
                    }
                });
    }
}
