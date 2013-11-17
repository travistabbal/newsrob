package com.grazerss.feedly;

import java.io.IOException;
import java.net.MalformedURLException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import javax.xml.parsers.FactoryConfigurationError;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.http.client.ClientProtocolException;
import org.xml.sax.SAXException;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.grazerss.ArticleDbState;
import com.grazerss.AuthenticationFailedException;
import com.grazerss.BackendProvider;
import com.grazerss.DB;
import com.grazerss.DiscoveredFeed;
import com.grazerss.Entry;
import com.grazerss.EntryManager;
import com.grazerss.Feed;
import com.grazerss.Label;
import com.grazerss.NeedsSessionException;
import com.grazerss.PL;
import com.grazerss.ReadState;
import com.grazerss.SyncJob;
import com.grazerss.download.HtmlEntitiesDecoder;
import com.grazerss.feedly.LatestRead.Feeds;
import com.grazerss.feedly.SearchFeedsResponse.Results;
import com.grazerss.feedly.StreamContentResponse.Item.Alternate;
import com.grazerss.feedly.UnreadCountResponse.UnreadCount;
import com.grazerss.jobs.Job;

public class FeedlyBackendProvider implements BackendProvider
{
  private Context             context;
  private FeedlyManager       api        = null;
  private EntryManager        entryManager;
  private SearchFeedsResponse searchResponse;

  private long                lastUpdate = -1;

  public FeedlyBackendProvider(Context context)
  {
    this.context = context.getApplicationContext();
  }

  @Override
  public boolean authenticate(Context context, String email, String password, String captchaToken, String captchaAnswer)
      throws ClientProtocolException, IOException, AuthenticationFailedException
  {
    api = new FeedlyManager(context.getApplicationContext());

    if (api.isTokenExpired())
    {
      return api.refreshToken();
    }
    else
    {
      return api.isTokenValid();
    }
  }

  @Override
  public void differentialUpdateOfArticlesStates(EntryManager entryManager, Job job, String stream, String excludeState,
      ArticleDbState articleDbState) throws SAXException, IOException, ParserConfigurationException, ServerBadRequestException,
      ServerBadRequestException, AuthenticationExpiredException
  {
    // TODO Auto-generated method stub

  }

  @Override
  public List<DiscoveredFeed> discoverFeeds(String query) throws SyncAPIException, IOException, ServerBadRequestException,
      ParserConfigurationException, SAXException, ServerBadRequestException, AuthenticationExpiredException
  {
    try
    {
      if (handleAuthenticate() == false)
      {
        return null;
      }

      searchResponse = api.searchFeeds(query);
      List<DiscoveredFeed> ret = new ArrayList<DiscoveredFeed>();

      for (Results f : searchResponse.results)
      {
        DiscoveredFeed df = new DiscoveredFeed();
        df.title = f.title;
        df.feedUrl = f.feedId;
        df.alternateUrl = f.website;
        ret.add(df);
      }

      return ret;
    }
    catch (Exception e)
    {
      String message = "Problem during discoverFeeds: " + e.getMessage();
      PL.log(message, context);
    }

    // TODO Auto-generated method stub
    return null;
  }

  @SuppressWarnings("unused")
  @Override
  public int fetchNewEntries(EntryManager entryManager, SyncJob job, boolean manualSync) throws ClientProtocolException, IOException,
      NeedsSessionException, SAXException, IllegalStateException, ParserConfigurationException, FactoryConfigurationError, SyncAPIException,
      ServerBadRequestException, AuthenticationExpiredException
  {
    if (handleAuthenticate() == false)
    {
      return 0;
    }

    lastUpdate = getEntryManager().getGRUpdated();
    Long localLastUpdate = lastUpdate;
    String continuation = null;

    int maxCapacity = getEntryManager().getNewsRobSettings().getStorageCapacity();
    int currentUnreadArticlesCount = getEntryManager().getUnreadArticleCountExcludingPinned();
    int maxDownload = maxCapacity - currentUnreadArticlesCount;
    int fetchedArticleCount = 0;

    job.setJobDescription("Fetching feed information");

    // Update the feed list, make sure we have feed records for everything...
    List<Feed> feeds = getEntryManager().findAllFeeds();
    List<Subscriptions> subscriptions = api.getSubscriptions();
    updateFeeds(subscriptions, feeds);

    // Get starred articles
    fetchStarredArticles(feeds, job);

    job.setJobDescription("Fetching new articles");
    getEntryManager().fireStatusUpdated();

    List<Entry> entriesToBeInserted = new ArrayList<Entry>(20);

    // Get unread counts
    UnreadCountResponse unreadResponse = api.getUnreadCounts();
    Integer unreadTotal = getTotalUnread(unreadResponse);

    job.target = Math.min(unreadTotal - currentUnreadArticlesCount, maxDownload);
    getEntryManager().fireStatusUpdated();

    while ((fetchedArticleCount <= maxDownload) && ((currentUnreadArticlesCount + fetchedArticleCount) <= maxCapacity))
    {
      StreamContentResponse content = api.getUnread(getEntryManager().shouldShowNewestArticlesFirst(), lastUpdate, 100, continuation);

      setLastUpdate(content.updated);
      continuation = content.continuation;

      // Store article data
      fetchedArticleCount = storeArticles(content, feeds, fetchedArticleCount, job);

      // Stop when the server says they don't have any more
      // We have what we think we need, or the user asks us to
      if ((continuation == null) || (job.actual >= job.target) || job.isCancelled())
      {
        break;
      }
    }

    job.actual = job.target;
    getEntryManager().fireStatusUpdated();
    getEntryManager().setGRUpdated(lastUpdate);

    // This might happen if they add feeds or old unread articles on the site. Do a full sync next time.
    currentUnreadArticlesCount = getEntryManager().getUnreadArticleCountExcludingPinned();
    if ((currentUnreadArticlesCount < unreadTotal) && (currentUnreadArticlesCount < maxCapacity))
    {
      lastUpdate = -1;
      getEntryManager().setGRUpdated(lastUpdate);
    }

    return fetchedArticleCount;
  }

  private int fetchStarredArticles(List<Feed> feeds, SyncJob job)
  {
    try
    {
      int fetchedArticlesCount = 0;
      int maxStarredArticles = getEntryManager().getNoOfStarredArticlesToKeep();
      job.setJobDescription("Fetching starred articles");

      long lastStarUpdate = getEntryManager().getLastStarredSync();
      StreamContentResponse content = api.getSaved(getEntryManager().shouldShowNewestArticlesFirst(), null, maxStarredArticles, null);
      getEntryManager().setLastStarredSync(Calendar.getInstance(TimeZone.getTimeZone("UTC")).getTimeInMillis());

      return storeArticles(content, feeds, fetchedArticlesCount, job);
    }
    catch (Exception e)
    {
      String message = "Problem during fetchStarredArticles: " + e.getMessage();
      PL.log(message, context);
    }
    return 0;
  }

  private final EntryManager getEntryManager()
  {
    if (entryManager == null)
    {
      entryManager = EntryManager.getInstance(context);
    }

    return entryManager;
  }

  private Feed getFeedFromAtomId(List<Feed> feeds, String atomId)
  {
    try
    {
      for (Feed feed : feeds)
      {
        if (atomId.equals(feed.getAtomId()))
        {
          return feed;
        }
      }
    }
    catch (Exception e)
    {
      String message = "Problem during getFeedFromAtomId: " + e.getMessage();
      PL.log(message, context);
    }

    return null;
  }

  private String getLink(List<Alternate> links)
  {
    for (Alternate link : links)
    {
      if ((link != null) && (link.href != null))
      {
        return link.href;
      }
    }

    return null;
  }

  @Override
  public String getServiceName()
  {
    return "Feedly";
  }

  @Override
  public String getServiceUrl()
  {
    return "http://www.feedly.com";
  }

  public Integer getTotalUnread(UnreadCountResponse resp)
  {
    for (UnreadCount count : resp.unreadcounts)
    {
      if ((count.id != null) && count.id.endsWith("global.all"))
      {
        return count.count;
      }
    }

    return 0;
  }

  private boolean handleAuthenticate()
  {
    try
    {
      return authenticate(this.context, null, null, null, null);
    }
    catch (Exception e)
    {
      String message = "Problem during handleAuthenticate: " + e.getMessage();
      PL.log(message, context);
    }

    return false;
  }

  private boolean isStarred(StreamContentResponse.Item item)
  {
    if (item.tags != null)
    {
      for (Categories c : item.tags)
      {
        if (c.id.endsWith("/tag/global.saved"))
        {
          return true;
        }
      }
    }

    return false;
  }

  @Override
  public void logout()
  {
    if (api != null)
    {
      api.logout();
      api = null;
    }

    getEntryManager().clearAuthToken();
    getEntryManager().setGoogleUserId(null);
  }

  private int remotelyAlterReadState(Collection<Entry> entries, final String column, String desiredState)
  {
    try
    {
      List<String> ids = new ArrayList<String>();

      for (Entry entry : entries)
      {
        ids.add(entry.getAtomId());
      }

      if (desiredState.equals("1"))
      {
        boolean success = api.markRead(ids);
        if (success)
        {
          getEntryManager().removePendingStateMarkers(ids, column);
          return entries.size();
        }
      }
      else if (desiredState.equals("0"))
      {
        boolean success = api.markUnRead(ids);
        if (success)
        {
          getEntryManager().removePendingStateMarkers(ids, column);
          return entries.size();
        }
      }
    }
    catch (Exception e)
    {
      String message = "Problem during marking entry as un-/read: " + e.getMessage();
      PL.log(message, context);
    }

    return 0;
  }

  private int remotelyAlterStarredState(Collection<Entry> entries, final String column, String desiredState)
  {
    List<String> entryIds = new ArrayList<String>(entries.size());

    for (Entry entry : entries)
    {
      entryIds.add(entry.getAtomId());
    }

    if (desiredState.equals("1"))
    {
      if (api.starItems(entryIds))
      {
        getEntryManager().removePendingStateMarkers(entryIds, column);
      }
    }
    else if (desiredState.equals("0"))
    {
      if (api.unStarItems(entryIds))
      {
        getEntryManager().removePendingStateMarkers(entryIds, column);
      }
    }

    return entries.size();
  }

  private int remotelyAlterState(Collection<Entry> entries, final String column, String desiredState)
  {
    if (column.equals(DB.Entries.READ_STATE_PENDING))
    {
      return remotelyAlterReadState(entries, column, desiredState);
    }
    else if (column.equals(DB.Entries.STARRED_STATE_PENDING))
    {
      return remotelyAlterStarredState(entries, column, desiredState);
    }

    return 0;
  }

  private synchronized void setLastUpdate(Long update)
  {
    if (getEntryManager().shouldShowNewestArticlesFirst())
    {
      if ((lastUpdate == -1) || ((update != null) && (update < lastUpdate)))
      {
        lastUpdate = update;
      }
    }
    else
    {
      if ((update != null) && (update > lastUpdate))
      {
        lastUpdate = update;
      }
    }
  }

  @Override
  public void startLogin(Activity activity, Context context)
  {
    activity.startActivity(new Intent().setClass(context, FeedlyLoginActivity.class));
  }

  private int storeArticles(StreamContentResponse content, List<Feed> feeds, int fetchedArticleCount, SyncJob job)
  {
    List<Entry> entriesToBeInserted = new ArrayList<Entry>(20);
    List<StateChange> stateChanges = new ArrayList<BackendProvider.StateChange>();

    for (StreamContentResponse.Item story : content.items)
    {
      // Don't save one we already have. Mark unread instead.
      Entry entry = getEntryManager().findEntryByAtomId(story.id);
      if (entry != null)
      {
        stateChanges.add(new StateChange(entry.getAtomId(), StateChange.STATE_READ, StateChange.OPERATION_REMOVE));
        continue;
      }

      // Find the content text. Might be in the summary.
      String contentText = "";

      if (story.content != null)
      {
        contentText = story.content.content;
      }
      else if (story.summary != null)
      {
        contentText = story.summary.content;
      }

      // Save the entry
      Entry newEntry = new Entry();
      newEntry.setAtomId(story.id);
      newEntry.setContentURL(getLink(story.alternate));
      newEntry.setContent(contentText);
      newEntry.setTitle(HtmlEntitiesDecoder.decodeString(story.title));
      newEntry.setReadState(ReadState.UNREAD);
      newEntry.setFeedAtomId(story.origin.streamId);
      newEntry.setAuthor(story.author);
      newEntry.setAlternateHRef(getLink(story.alternate));
      newEntry.setHash(story.id);
      newEntry.setStarred(isStarred(story));
      newEntry.setUpdated(story.crawled == null ? new Date().getTime() : story.crawled);
      setLastUpdate(story.crawled);

      // Fill in some data from the feed record....
      Feed nrFeed = getFeedFromAtomId(feeds, story.origin.streamId);

      if (nrFeed != null)
      {
        newEntry.setFeedId(nrFeed.getId());
        newEntry.setDownloadPref(nrFeed.getDownloadPref());
        newEntry.setDisplayPref(nrFeed.getDisplayPref());

        if (story.categories != null)
        {
          for (Categories label : story.categories)
          {
            newEntry.addLabel(new Label(label.label));
          }
        }
      }

      entriesToBeInserted.add(newEntry);

      if (entriesToBeInserted.size() > 20)
      {
        job.actual = Math.min(fetchedArticleCount, job.target);
        getEntryManager().fireStatusUpdated();

        getEntryManager().insert(entriesToBeInserted);
        entriesToBeInserted.clear();
        getEntryManager().fireModelUpdated();
      }

      fetchedArticleCount++;
    }

    if (entriesToBeInserted.size() > 0)
    {
      getEntryManager().insert(entriesToBeInserted);
      entriesToBeInserted.clear();
    }

    if (stateChanges.size() > 0)
    {
      getEntryManager().updateStates(stateChanges);
      stateChanges.clear();
    }

    return fetchedArticleCount;
  }

  @Override
  public boolean submitSubscribe(String url2subscribe) throws SyncAPIException
  {
    try
    {
      if (handleAuthenticate() == false)
      {
        return false;
      }

      if (url2subscribe == null)
      {
        return false;
      }

      if (searchResponse != null)
      {
        for (Results r : searchResponse.results)
        {
          if (url2subscribe.equals(r.feedId))
          {
            return (api.subscribeToFeed(r.feedId, r.title, null));
          }
        }
      }
    }
    catch (Exception e)
    {
      String message = "Problem during syncArticles: " + e.getMessage();
      PL.log(message, context);
    }

    return false;
  }

  @Override
  public int synchronizeArticles(EntryManager entryManager, SyncJob syncJob) throws MalformedURLException, IOException,
      ParserConfigurationException, FactoryConfigurationError, SAXException, ParseException, NeedsSessionException, ParseException
  {
    try
    {
      if (handleAuthenticate() == false)
      {
        return 0;
      }

      syncServerReadStates(entryManager, syncJob);

      int noOfUpdated = 0;

      String[] fields = { DB.Entries.READ_STATE_PENDING, DB.Entries.STARRED_STATE_PENDING
      // DB.Entries.PINNED_STATE_PENDING
      };
      for (String f : fields)
      {

        String progressLabel;
        if (f == DB.Entries.READ_STATE_PENDING)
        {
          progressLabel = "read";
        }
        else if (f == DB.Entries.STARRED_STATE_PENDING)
        {
          progressLabel = "starred";
        }
        else if (f == DB.Entries.PINNED_STATE_PENDING)
        {
          progressLabel = "pinned";
        }
        else
        {
          progressLabel = "unknown";
        }

        String[] desiredStates = { "0", "1" };
        for (String desiredState : desiredStates)
        {
          List<Entry> allEntries = getEntryManager().findAllStatePendingEntries(f, desiredState);

          if (allEntries.size() == 0)
          {
            continue;
          }

          syncJob.setJobDescription("Syncing state: " + progressLabel);
          syncJob.target = allEntries.size();
          syncJob.actual = 0;
          getEntryManager().fireStatusUpdated();

          // LATER make this cancelable? Add Job here.

          int offset = 0;

          while (offset < allEntries.size())
          {
            int nextPackSize = Math.min(allEntries.size() - offset, 25);
            if (nextPackSize == 0)
            {
              break;
            }

            List<Entry> currentPack = new ArrayList<Entry>(allEntries.subList(offset, offset + nextPackSize));
            offset += nextPackSize;
            noOfUpdated += remotelyAlterState(currentPack, f, desiredState);
            syncJob.actual = noOfUpdated;
            getEntryManager().fireStatusUpdated();
          }
        }
      }
      return noOfUpdated;
    }
    catch (Exception e)
    {
      String message = "Problem during syncArticles: " + e.getMessage();
      PL.log(message, context);
    }
    return 0;
  }

  private void syncServerReadStates(EntryManager entryManager, SyncJob job)
  {
    try
    {
      job.setJobDescription("Syncing server read states");
      List<StateChange> stateChanges = new ArrayList<BackendProvider.StateChange>();

      long lastReadUpdate = getEntryManager().getLastReadSync();
      LatestRead latest = api.getLatestRead(lastReadUpdate);
      getEntryManager().setLastReadSync(Math.max(lastReadUpdate, Calendar.getInstance(TimeZone.getTimeZone("UTC")).getTimeInMillis()));

      for (String entryId : latest.entries)
      {
        stateChanges.add(new StateChange(entryId, StateChange.STATE_READ, StateChange.OPERATION_ADD));
      }

      for (Feeds feed : latest.feeds)
      {
        if (feed.id != null)
        {
          Feed localFeed = getEntryManager().findFeedByAtomId(feed.id);

          if (localFeed != null)
          {
            if ((feed.asOf != null) && (feed.asOf > lastReadUpdate))
            {
              getEntryManager().setLastReadSync(feed.asOf);
              lastReadUpdate = feed.asOf;

              List<Entry> entries = getEntryManager().findArticlesForFeedId(localFeed.getId());

              for (Entry entry : entries)
              {
                stateChanges.add(new StateChange(entry.getAtomId(), StateChange.STATE_READ, StateChange.OPERATION_ADD));
              }
            }
          }
        }

        getEntryManager().updateStates(stateChanges);
      }

      job.setJobDescription("Server read states synced");
    }
    catch (Exception e)
    {
      String message = "Problem during syncServerReadStates: " + e.getMessage();
      PL.log(message, context);
    }
  }

  @Override
  public void unsubscribeFeed(String feedAtomId) throws IOException, NeedsSessionException, SyncAPIException
  {
    try
    {
      if (handleAuthenticate() == false)
      {
        return;
      }

      if (api.deleteSubscription(feedAtomId) == false)
      {
        throw new SyncAPIException("Feedly unsubscribe request was not successfull");
      }
    }
    catch (Exception e)
    {
      String message = "Problem during unsubscribeFeed: " + e.getMessage();
      PL.log(message, context);
    }
  }

  private void updateFeeds(List<Subscriptions> remoteFeeds, List<Feed> feeds)
  {
    for (Subscriptions remoteFeed : remoteFeeds)
    {
      boolean found = false;

      for (Feed nrFeed : feeds)
      {
        if ((nrFeed != null) && nrFeed.getAtomId().equals(remoteFeed.id))
        {
          found = true;
          break;
        }
      }

      if (found == false)
      {
        Feed newFeed = new Feed();
        newFeed.setAtomId(remoteFeed.id);
        newFeed.setTitle(remoteFeed.title);
        newFeed.setUrl(remoteFeed.website);
        newFeed.setDownloadPref(Feed.DOWNLOAD_PREF_DEFAULT);
        newFeed.setDisplayPref(Feed.DISPLAY_PREF_DEFAULT);

        long id = getEntryManager().insert(newFeed);
        newFeed.setId(id);

        feeds.add(newFeed);
      }
    }
  }

  @Override
  public void updateSubscriptionList(EntryManager entryManager, Job job) throws IOException, ParserConfigurationException, SAXException,
      ServerBadRequestException, AuthenticationExpiredException
  {
    job.setJobDescription("Fetching feed information");

    // Update the feed list, make sure we have feed records for everything...
    List<Feed> feeds = getEntryManager().findAllFeeds();
    List<Subscriptions> subscriptions = api.getSubscriptions();
    updateFeeds(subscriptions, feeds);
  }

}
