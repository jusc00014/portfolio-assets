from django.db.models import Q, Exists, OuterRef, When, IntegerField, FloatField, Count, ExpressionWrapper, Case, Value, F, Prefetch

from fame.models import Fame, FameLevels, FameUsers, ExpertiseAreas
from socialnetwork.models import Posts, SocialNetworkUsers


# general methods independent of html and REST views
# should be used by REST and html views


def _get_social_network_user(user) -> SocialNetworkUsers:
    """Given a FameUser, gets the social network user from the request. Assumes that the user is authenticated."""
    try:
        user = SocialNetworkUsers.objects.get(id=user.id)
    except SocialNetworkUsers.DoesNotExist:
        raise PermissionError("User does not exist")
    return user


def timeline(user: SocialNetworkUsers, start: int = 0, end: int = None, published=True, community_mode=False):
    """Get the timeline of the user. Assumes that the user is authenticated."""

    if community_mode:
        # T4
        # in community mode, posts of communities are displayed if ALL of the following criteria are met:
        # 1. the author of the post is a member of the community
        # 2. the user is a member of the community
        # 3. the post contains the community’s expertise area
        # 4. the post is published or the user is the author

        pass
        #+ We store the id of the posts that should be displayed in a list
        posts_id = []
        #+ We look at all posts ...
        for post in Posts.objects.all():
            #+ ... and what expertise areas they belong to
            for ea in post.expertise_area_and_truth_ratings.all():
                #+ The post will be among the published ones if for one expertise-area ...
                if (
                    #+ ... the user is in the community and ...
                    ea in user.communities.all() and
                    #+ ... the author is in the community and ...
                    ea in post.author.communities.all() and
                    #+ ... eighter the post is public or the user himself is the author
                    (post.published or post.author == user)
                ):
                    posts_id.append(post.id)
                    break
        #+ Filter our all Post-objects that have a matching id and order them by the time they were submitted
        posts = Posts.objects.filter(id__in=posts_id).order_by("-submitted")

    else:
        # in standard mode, posts of followed users are displayed
        _follows = user.follows.all()
        posts = Posts.objects.filter(
            (Q(author__in=_follows) & Q(published=published)) | Q(author=user)
        ).order_by("-submitted")
    if end is None:
        return posts[start:]
    else:
        return posts[start:end+1]


def search(keyword: str, start: int = 0, end: int = None, published=True):
    """Search for all posts in the system containing the keyword. Assumes that all posts are public"""
    posts = Posts.objects.filter(
        Q(content__icontains=keyword)
        | Q(author__email__icontains=keyword)
        | Q(author__first_name__icontains=keyword)
        | Q(author__last_name__icontains=keyword),
        published=published,
    ).order_by("-submitted")
    if end is None:
        return posts[start:]
    else:
        return posts[start:end+1]


def follows(user: SocialNetworkUsers, start: int = 0, end: int = None):
    """Get the users followed by this user. Assumes that the user is authenticated."""
    _follows = user.follows.all()
    if end is None:
        return _follows[start:]
    else:
        return _follows[start:end+1]


def followers(user: SocialNetworkUsers, start: int = 0, end: int = None):
    """Get the followers of this user. Assumes that the user is authenticated."""
    _followers = user.followed_by.all()
    if end is None:
        return _followers[start:]
    else:
        return _followers[start:end+1]


def follow(user: SocialNetworkUsers, user_to_follow: SocialNetworkUsers):
    """Follow a user. Assumes that the user is authenticated. If user already follows the user, signal that."""
    if user_to_follow in user.follows.all():
        return {"followed": False}
    user.follows.add(user_to_follow)
    user.save()
    return {"followed": True}


def unfollow(user: SocialNetworkUsers, user_to_unfollow: SocialNetworkUsers):
    """Unfollow a user. Assumes that the user is authenticated. If user does not follow the user anyway, signal that."""
    if user_to_unfollow not in user.follows.all():
        return {"unfollowed": False}
    user.follows.remove(user_to_unfollow)
    user.save()
    return {"unfollowed": True}


def submit_post(
    user: SocialNetworkUsers,
    content: str,
    cites: Posts = None,
    replies_to: Posts = None,
):
    """Submit a post for publication. Assumes that the user is authenticated.
    returns a tuple of three elements:
    1. a dictionary with the keys "published" and "id" (the id of the post)
    2. a list of dictionaries containing the expertise areas and their truth ratings
    3. a boolean indicating whether the user was banned and logged out and should be redirected to the login page
    """

    # create post  instance:
    post = Posts.objects.create(
        content=content,
        author=user,
        cites=cites,
        replies_to=replies_to,
    )

    # classify the content into expertise areas:
    # only publish the post if none of the expertise areas contains bullshit:
    _at_least_one_expertise_area_contains_bullshit, _expertise_areas = (
        post.determine_expertise_areas_and_truth_ratings()
    )
    post.published = not _at_least_one_expertise_area_contains_bullshit

    redirect_to_logout = False
    
    #+ Store all for the post relevant expertise_areas in a list
    expertise_areas = [epa_dict["expertise_area"] for epa_dict in _expertise_areas]
    #+ In the following we only work with fame-objects that belong to the user and a relevant expertise-area
    #+ Here we filter for all Fame-objects to the user and expertise_areas so, we can reuse it for T1 and T2
    relevant_fame_objects = Fame.objects.filter(user=user, expertise_area__in=expertise_areas)
        #+ for T1 alone Fame.objects.filter(user=user, expertise_area__in=expertise_areas, fame_level__numeric_value__lt = 0).exists() would be more efficient
    #+ T1: Block publishing if user has negative fame in any expertise area of post
    #+ If one of the relevant Fame-objects has a negative fame_level do not publish
    if relevant_fame_objects.filter(fame_level__numeric_value__lt = 0).exists():
        post.published = False
    post.save

    #+ T2: Adjust fame if any expertise area has negative truth rating
    #+ If the AI detected some bullshit
    if _at_least_one_expertise_area_contains_bullshit:
        #+ For every object in the _expertise_areas that the AI detected and classified
        for epa_dict in _expertise_areas:
            expertise_area = epa_dict["expertise_area"]
            truth_rating = epa_dict["truth_rating"]
            #+ If it determined a truth rating and this one is negative
            if truth_rating and truth_rating.numeric_value < 0:
                #+ Get the (first) relevant fame-object (the fame object of the user to the corresponding area)
                fame_obj = relevant_fame_objects.filter(expertise_area=expertise_area).first()
                #+ If the user already has a fame-value for this expertise-area
                if fame_obj:
                    #+ We try to lower the fame-level
                    try:
                        #+ T2a: Lower fame level
                        fame_obj.fame_level = fame_obj.fame_level.get_next_lower_fame_level()
                        fame_obj.save()
                    #+ If it is not possible to lower the fame-value, the method get_next_lower_fame_level will rase an exception
                    except ValueError:
                        #+ T2c: Cannot lower any further → ban user
                        user.is_banned = True
                        
                        #+ Logout the user
                        user.is_active = False
                        user.save()
                        redirect_to_logout = True

                        #+ Unpublish all their posts
                        Posts.objects.filter(author=user).update(published=False)

                        break
                    #+ Remove from community if fame dropped below Super Pro
                    if fame_obj.fame_level.numeric_value < FameLevels.objects.get(name="Super Pro").numeric_value:
                        leave_community(user, expertise_area)

                else:
                    # T2b: Add Confuser level if not in fame profile
                    confuser_level = FameLevels.objects.get(name="Confuser")
                    #+ Creates a new Fame-object with already excisting confuser_level, user and expertise_area as fields
                    Fame.objects.create(
                        user=user,
                        expertise_area=expertise_area,
                        fame_level=confuser_level,
                    )
    post.save()

    return (
        {"published": post.published, "id": post.id},
        _expertise_areas,
        redirect_to_logout,
    )


def rate_post(
    user: SocialNetworkUsers, post: Posts, rating_type: str, rating_score: int
):
    """Rate a post. Assumes that the user is authenticated. If user already rated the post with the given rating_type,
    update that rating score."""
    user_rating = None
    try:
        user_rating = user.userratings_set.get(post=post, rating_type=rating_type)
    except user.userratings_set.model.DoesNotExist:
        pass

    if user == post.author:
        raise PermissionError(
            "User is the author of the post. You cannot rate your own post."
        )

    if user_rating is not None:
        # update the existing rating:
        user_rating.rating_score = rating_score
        user_rating.save()
        return {"rated": True, "type": "update"}
    else:
        # create a new rating:
        user.userratings_set.add(
            post,
            through_defaults={"rating_type": rating_type, "rating_score": rating_score},
        )
        user.save()
        return {"rated": True, "type": "new"}


def fame(user: SocialNetworkUsers):
    """Get the fame of a user. Assumes that the user is authenticated."""
    try:
        user = SocialNetworkUsers.objects.get(id=user.id)
    except SocialNetworkUsers.DoesNotExist:
        raise ValueError("User does not exist")

    return user, Fame.objects.filter(user=user)


def bullshitters():
    """Return a Python dictionary mapping each existing expertise area in the fame profiles to a list of the users
    having negative fame for that expertise area. Each list should contain Python dictionaries as entries with keys
    ``user'' (for the user) and ``fame_level_numeric'' (for the corresponding fame value), and should be ranked, i.e.,
    users with the lowest fame are shown first, in case there is a tie, within that tie sort by date_joined
    (most recent first). Note that expertise areas with no expert may be omitted.
    """
    pass

    #+ T3
    #+ The output will be of type: Dict of (Lists of Dicts)
    #+  {expertise_area(1): [{"user": user(1), "fame_level_numeric": nr(1)}, {"user": user(2), "fame_level_numeric": nr(2)}, ...], 
    #+    expertise_area(2): [{"user": user(3), "fame_level_numeric": nr(3)}, {"user": user(4), "fame_level_numeric": nr(4)}, ...], ...}
    negative_users_dict = {}
    for fam in Fame.objects.all():
        #+ If we found a fame-entry with negative entry ...
        if fam.fame_level.numeric_value < 0:
            #+ if the area is already contained in the Dict we append also this user to whom the fame-entry belongs
            if (fam.expertise_area in negative_users_dict):
                negative_users_dict[fam.expertise_area].append({"user": fam.user, "fame_level_numeric": fam.fame_level.numeric_value})
            #+ if the area is contained in the Dict we create a dict entry and the data, which is al list containing only this user to whom the fame-entry belongs
            else:
                negative_users_dict[fam.expertise_area] = [{"user": fam.user, "fame_level_numeric": fam.fame_level.numeric_value}]
    #+ For all expertise-areas to which there are negative fame-entrys ...
    for exa in negative_users_dict:
        #+ we sort their data by the entry at "fame_level_numeric" or by date_joined
        negative_users_dict[exa].sort(
            #+ The key for the sorting needs to be defined as al function mapping the entry (one entry of the list)
            key=lambda entry: (entry["fame_level_numeric"], -entry["user"].date_joined.timestamp())
        )
    return(negative_users_dict)
    
    #########################
    # T3 Alternative
    # add your code here
    # from collections import defaultdict

    # result = defaultdict(list)

    # # Get all Fame entries with negative fame levels
    # negative_fame_entries = (
    #     Fame.objects
    #     .select_related("user", "expertise_area", "fame_level")
    #     .filter(fame_level__numeric_value__lt=0)
    # )

    # # Group users per expertise area
    # for fame_entry in negative_fame_entries:
    #     result[fame_entry.expertise_area].append({
    #         "user": fame_entry.user,
    #         "fame_level_numeric": fame_entry.fame_level.numeric_value,
    #     })

    # # Sort each list by fame level ascending, then date_joined descending
    # for expertise_area in result:
    #     result[expertise_area].sort(
    #         key=lambda entry: (entry["fame_level_numeric"], -entry["user"].date_joined.timestamp())
    #     )

    # return dict(result)
    #########################





def join_community(user: SocialNetworkUsers, community: ExpertiseAreas):
    """Join a specified community. Note that this method does not check whether the user is eligible for joining the
    community.
    """
    pass
    #########################
    # add your code here
    user.communities.add(community)
    user.save()
    #########################



def leave_community(user: SocialNetworkUsers, community: ExpertiseAreas):
    """Leave a specified community."""
    pass
    #########################
    # add your code here
    user.communities.remove(community)
    user.save()
    #########################



def similar_users(user: SocialNetworkUsers):
    """Compute the similarity of user with all other users. The method returns a QuerySet of FameUsers annotated
    with an additional field 'similarity'. Sort the result in descending order according to 'similarity', in case
    there is a tie, within that tie sort by date_joined (most recent first)"""

    #+ users is a dict of the form: {'user1': {expertise_area1: num1, expertise_area2: num2, ...}, ...}
    users = {}
    similarities = []
    found = True
    for nutzer in FameUsers.objects.all():
        #+ As user is a SocialNetworkUser, we map it to a FameUser to map it to the dict
        if nutzer.id == user.id:
            found = False
            user = nutzer
        users[nutzer] = {}
    #+ If the user is not part of the FameUser's, there will be no similar users (the formula gives division by zero)
    if found:
        return(None)
    #+ Sort in all the Fame-objects into the dict
    for fam in Fame.objects.all():
        users[fam.user][fam.expertise_area] = fam.fame_level.numeric_value
    #+ current_user will be a dict mapping expertise_areas to fame-values
    current_user = users[user]
    #+ count = number of expertise-areas that the user has
    count = len(current_user)
    #+ If we don't have to divide by zero
    if count != 0:
        #+ For all FameUsers...
        for nutzer in users:
            summ = 0
            #+ ... that are not the current user
            if not (nutzer.email == user.username):
                for ex in current_user:
                    #+ If the nutzer does not have a specific expertise area that the user has, the predicate will be false and those cases can therefore be ignored
                    if ex in users[nutzer]:
                        if abs(current_user[ex]-users[nutzer][ex]) <= 100:
                            summ = summ + 1
                summ = summ/count
                if summ != 0:
                    #+ similarities will be a list of pairs containing a FameUser and his similarity to the user
                    similarities.append((nutzer, summ))
    #+ For all the FameUsers take those who have some similarity to the user ...
    qs = FameUsers.objects.filter(email__in=[nutzer.email for nutzer, _ in similarities]
    ).annotate( #+ And add them a field for the similarity-value to the user called 'similarity'
        similarity=Case(
            *[When(email=nutzer.email, then=Value(score)) for nutzer, score in similarities],
            output_field=FloatField()
        )   #+ Order the generated QuerySet by similarity and date_joined
    ).order_by('-similarity', '-date_joined')
    return(qs)

    pass
    #########################
    # add your code here
    # ui_fame = {
    #     fame.expertise_area_id: fame.fame_level.numeric_value
    #     for fame in Fame.objects.filter(user=user)
    # }

    # Ei = list(ui_fame.keys())

    # if not Ei:
    #     return SocialNetworkUsers.objects.none()

    # similarity_scores = []

    # other_users = SocialNetworkUsers.objects.exclude(id=user.id)

    # for uj in other_users:
    #     uj_fame = {
    #         fame.expertise_area_id: fame.fame_level.numeric_value
    #         for fame in Fame.objects.filter(user=uj)
    #     }

    #     match_count = 0
    #     for e in Ei:
    #         fame_ui = ui_fame[e]
    #         fame_uj = uj_fame.get(e, float('inf'))  # ∞ if uj has no fame in this area
    #         if abs(fame_ui - fame_uj) <= 100:
    #             match_count += 1

    #     score = match_count / len(Ei)
    #     if score > 0:
    #         similarity_scores.append((uj, score))

    # # Sort: first by similarity DESC, then by date_joined DESC
    # similarity_scores.sort(key=lambda x: (-x[1], -x[0].date_joined.timestamp()))

    # # Create lookup for fast access
    # score_lookup = {usr.id: score for usr, score in similarity_scores}
    # user_ids_ordered = [usr.id for usr, _ in similarity_scores]

    # # Query actual user objects
    # result = list(SocialNetworkUsers.objects.filter(id__in=user_ids_ordered))

    # # Preserve order and attach .similarity field
    # result.sort(key=lambda u: (-score_lookup[u.id], -u.date_joined.timestamp()))
    # for u in result:
    #     u.similarity = score_lookup[u.id]

    # return result
    #########################

