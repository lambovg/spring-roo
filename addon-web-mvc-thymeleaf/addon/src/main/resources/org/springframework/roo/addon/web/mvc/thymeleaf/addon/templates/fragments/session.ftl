<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8" />
    <title>Session</title>
  </head>
  <body>
    <div data-layout-fragment="session">
        <div class="container upper-nav">
            <div class="session">
              <div data-th-text="${r"#{"}label_user${r"}"}"><span class="glyphicon glyphicon-user" aria-hidden="true"></span>User</div>
              <div data-th-text="${r"#{"}label_last_access(00-00-0000)${r"}"}"><span class="glyphicon glyphicon-calendar" aria-hidden="true"></span>Last Access: 00-00-0000</div>
              <button type="submit" class="exit" data-th-text="${r"#{"}label_exit${r"}"}"><span class="glyphicon glyphicon-off" aria-hidden="true"></span>Exit</button>
            </div>
        </div>
      </div>
  </body>
</html>